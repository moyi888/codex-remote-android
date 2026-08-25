package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/api"
	"github.com/moyi888/codex-remote-android/bridge/internal/auth"
	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
	"github.com/moyi888/codex-remote-android/bridge/internal/commands"
	"github.com/moyi888/codex-remote-android/bridge/internal/config"
	"github.com/moyi888/codex-remote-android/bridge/internal/events"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

type serveOptions struct {
	listen      string
	data        string
	fake        bool
	allowPublic bool
}

func parseServeOptions(args []string) (serveOptions, error) {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	var options serveOptions
	flags.StringVar(&options.listen, "listen", "127.0.0.1:8787", "Bridge listen address")
	flags.StringVar(&options.data, "data", "data/bridge.db", "SQLite database path")
	flags.BoolVar(&options.fake, "fake", false, "Use the development Codex adapter")
	flags.BoolVar(&options.allowPublic, "allow-public-listen", false, "Allow non-Tailscale listeners")
	if err := flags.Parse(args); err != nil {
		return serveOptions{}, err
	}
	if err := config.ValidateListenAddress(options.listen, options.allowPublic); err != nil {
		return serveOptions{}, err
	}
	return options, nil
}

type bridgeRuntime struct {
	handler      http.Handler
	pairingToken string
	store        *store.Store
}

func newRuntime(databasePath string, adapter codex.Adapter) (*bridgeRuntime, error) {
	database, err := store.Open(databasePath)
	if err != nil {
		return nil, err
	}
	pairing := auth.NewPairingService(database, time.Now)
	token, err := pairing.Issue(5 * time.Minute)
	if err != nil {
		database.Close()
		return nil, err
	}
	broker := events.NewBroker(database, time.Now)
	commandService := commands.NewService(database, codex.NewCommandExecutor(adapter))
	server := api.NewServer(pairing, adapter, api.WithCommands(commandService), api.WithEvents(broker))
	return &bridgeRuntime{handler: server.Handler(), pairingToken: token, store: database}, nil
}

func (r *bridgeRuntime) Close() error { return r.store.Close() }

func runServe(options serveOptions) error {
	if err := os.MkdirAll(filepath.Dir(options.data), 0o700); err != nil {
		return err
	}
	var adapter codex.Adapter
	if options.fake {
		adapter = codex.NewFakeAdapter()
	} else {
		return fmt.Errorf("real Codex runtime requires an explicit project registry; use --fake in this alpha build")
	}
	runtime, err := newRuntime(options.data, adapter)
	if err != nil {
		return err
	}
	defer runtime.Close()

	server := &http.Server{
		Addr: options.listen, Handler: runtime.handler,
		ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()

	fmt.Printf("Bridge listening on http://%s\n", options.listen)
	fmt.Printf("One-time pairing token (expires in 5 minutes): %s\n", runtime.pairingToken)
	err = server.ListenAndServe()
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}

var version = "dev"

func versionText(value string) string {
	return "codex-remote " + value
}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "version" {
		fmt.Println(versionText(version))
		return
	}
	if len(os.Args) >= 2 && os.Args[1] == "serve" {
		options, err := parseServeOptions(os.Args[2:])
		if err == nil {
			err = runServe(options)
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}

	fmt.Fprintln(os.Stderr, "usage: codex-remote <version|serve>")
	os.Exit(2)
}

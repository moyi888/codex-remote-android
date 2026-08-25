package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
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
	listen       string
	advertiseURL string
	data         string
	fake         bool
	allowPublic  bool
}

func parseServeOptions(args []string) (serveOptions, error) {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	var options serveOptions
	flags.StringVar(&options.listen, "listen", "127.0.0.1:8787", "Bridge listen address")
	flags.StringVar(&options.advertiseURL, "advertise-url", "", "Client-reachable Bridge HTTP(S) origin")
	flags.StringVar(&options.data, "data", "data/bridge.db", "SQLite database path")
	flags.BoolVar(&options.fake, "fake", false, "Use the development Codex adapter")
	flags.BoolVar(&options.allowPublic, "allow-public-listen", false, "Allow non-Tailscale listeners")
	if err := flags.Parse(args); err != nil {
		return serveOptions{}, err
	}
	if err := config.ValidateListenAddress(options.listen, options.allowPublic); err != nil {
		return serveOptions{}, err
	}
	advertiseURL, err := normalizeAdvertiseURL(options.listen, options.advertiseURL)
	if err != nil {
		return serveOptions{}, err
	}
	options.advertiseURL = advertiseURL
	return options, nil
}

func normalizeAdvertiseURL(listenAddress, explicit string) (string, error) {
	if explicit == "" {
		host, _, err := net.SplitHostPort(listenAddress)
		if err != nil {
			return "", fmt.Errorf("invalid listen address: %w", err)
		}
		ip, err := netip.ParseAddr(host)
		if err != nil || ip.IsLoopback() || ip.IsUnspecified() {
			return "", fmt.Errorf("listen address is not client-reachable; provide --advertise-url")
		}
		return (&url.URL{Scheme: "http", Host: listenAddress}).String(), nil
	}

	parsed, err := url.Parse(explicit)
	if err != nil {
		return "", fmt.Errorf("invalid advertise URL")
	}
	if (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Hostname() == "" {
		return "", fmt.Errorf("advertise URL must be an HTTP(S) origin with a host")
	}
	if parsed.User != nil || (parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.ForceQuery || strings.Contains(explicit, "#") {
		return "", fmt.Errorf("advertise URL must be a pure origin")
	}
	if strings.HasSuffix(parsed.Host, ":") {
		return "", fmt.Errorf("advertise URL port is invalid")
	}
	if port := parsed.Port(); port != "" {
		value, err := strconv.Atoi(port)
		if err != nil || value < 1 || value > 65535 {
			return "", fmt.Errorf("advertise URL port is invalid")
		}
	}
	if ip, err := netip.ParseAddr(parsed.Hostname()); err == nil && ip.IsUnspecified() {
		return "", fmt.Errorf("advertise URL must not use an unspecified IP address")
	}
	parsed.Path = ""
	parsed.RawPath = ""
	return parsed.String(), nil
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

func pairingInvitationURL(advertiseBaseURL, token string) (string, error) {
	invitation := url.URL{Scheme: "codex-remote", Host: "pair"}
	query := invitation.Query()
	query.Set("baseUrl", advertiseBaseURL)
	query.Set("token", token)
	invitation.RawQuery = query.Encode()
	return invitation.String(), nil
}

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
	pairingLink, err := pairingInvitationURL(options.advertiseURL, runtime.pairingToken)
	if err != nil {
		return err
	}

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
	fmt.Printf("Pairing link: %s\n", pairingLink)
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

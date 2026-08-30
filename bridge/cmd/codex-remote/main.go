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
	"github.com/moyi888/codex-remote-android/bridge/internal/desktop"
	"github.com/moyi888/codex-remote-android/bridge/internal/events"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

type serveOptions struct {
	listen          string
	advertiseURL    string
	data            string
	projects        string
	historyProjects bool
	codexCommand    string
	fake            bool
	allowPublic     bool
}

type applicationMode string

const (
	desktopMode applicationMode = "desktop"
	serveMode   applicationMode = "serve"
	versionMode applicationMode = "version"
)

func selectApplicationMode(args []string) (applicationMode, error) {
	if len(args) == 0 {
		return desktopMode, nil
	}
	switch args[0] {
	case "serve":
		return serveMode, nil
	case "version":
		return versionMode, nil
	default:
		return "", fmt.Errorf("unknown command %q", args[0])
	}
}

type applicationActions struct {
	desktop func() error
	serve   func(serveOptions) error
	version func() error
}

func runApplication(args []string, actions applicationActions) error {
	mode, err := selectApplicationMode(args)
	if err != nil {
		return err
	}
	switch mode {
	case desktopMode:
		return actions.desktop()
	case serveMode:
		options, err := parseServeOptions(args[1:])
		if err != nil {
			return err
		}
		return actions.serve(options)
	case versionMode:
		if len(args) != 1 {
			return fmt.Errorf("version does not accept arguments")
		}
		if actions.version != nil {
			return actions.version()
		}
		return nil
	default:
		return fmt.Errorf("unsupported application mode")
	}
}

type appServerRuntime interface {
	codex.RPCTransport
	Close() error
}

type appServerStarter func(context.Context, string, []string, []string) (appServerRuntime, error)

func parseServeOptions(args []string) (serveOptions, error) {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	var options serveOptions
	flags.StringVar(&options.listen, "listen", "127.0.0.1:8787", "Bridge listen address")
	flags.StringVar(&options.advertiseURL, "advertise-url", "", "Client-reachable Bridge HTTP(S) origin")
	flags.StringVar(&options.data, "data", "data/bridge.db", "SQLite database path")
	flags.StringVar(&options.projects, "projects", "", "Path to the allowed project registry JSON")
	flags.BoolVar(&options.historyProjects, "history-projects", false, "Derive allowed projects from Codex thread history")
	flags.StringVar(&options.codexCommand, "codex-command", "codex", "Codex executable path")
	flags.BoolVar(&options.fake, "fake", false, "Use the development Codex adapter")
	flags.BoolVar(&options.allowPublic, "allow-public-listen", false, "Allow non-Tailscale listeners")
	if err := flags.Parse(args); err != nil {
		return serveOptions{}, err
	}
	if !options.fake && options.projects == "" && !options.historyProjects {
		return serveOptions{}, fmt.Errorf("real Codex runtime requires --projects or --history-projects")
	}
	if options.projects != "" && options.historyProjects {
		return serveOptions{}, fmt.Errorf("choose one project source: --projects or --history-projects")
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

func startRealAdapter(
	ctx context.Context,
	options serveOptions,
	starter appServerStarter,
) (codex.Adapter, appServerRuntime, error) {
	var projects []codex.Project
	if !options.historyProjects {
		var err error
		projects, err = codex.LoadProjects(options.projects)
		if err != nil {
			return nil, nil, err
		}
	}
	runtime, err := starter(ctx, options.codexCommand, []string{"app-server"}, nil)
	if err != nil {
		return nil, nil, fmt.Errorf("start Codex app-server: %w", err)
	}
	initializeContext, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := codex.InitializeTransport(initializeContext, runtime, version); err != nil {
		_ = runtime.Close()
		return nil, nil, fmt.Errorf("initialize Codex app-server: %w", err)
	}
	if options.historyProjects {
		return codex.NewHistoryAppServerAdapter(runtime), runtime, nil
	}
	return codex.NewAppServerAdapter(runtime, projects), runtime, nil
}

func startAppServerProcess(
	ctx context.Context,
	command string,
	args, environment []string,
) (appServerRuntime, error) {
	return codex.StartRPCProcess(ctx, command, args, environment)
}

func normalizeAdvertiseURL(listenAddress, explicit string) (string, error) {
	if explicit == "" {
		host, _, err := net.SplitHostPort(listenAddress)
		if err != nil {
			return "", fmt.Errorf("invalid listen address: %w", err)
		}
		ip, err := netip.ParseAddr(host)
		if err != nil {
			return "", fmt.Errorf("listen host must be an IP address")
		}
		if ip.IsLoopback() {
			return "", nil
		}
		if ip.IsUnspecified() {
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
	if !validAdvertiseHost(parsed.Hostname()) {
		return "", fmt.Errorf("advertise URL host must be an IP address or valid ASCII DNS name")
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

func validAdvertiseHost(host string) bool {
	if _, err := netip.ParseAddr(host); err == nil {
		return true
	}
	if len(host) == 0 || len(host) > 253 {
		return false
	}
	labels := strings.Split(host, ".")
	allNumeric := true
	for _, label := range labels {
		allNumeric = allNumeric && isASCIIDigits(label)
	}
	if allNumeric || (len(labels) > 1 && isASCIIDigits(labels[len(labels)-1])) {
		return false
	}
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, character := range label {
			if (character < 'a' || character > 'z') &&
				(character < 'A' || character > 'Z') &&
				(character < '0' || character > '9') && character != '-' {
				return false
			}
		}
	}
	return true
}

func isASCIIDigits(value string) bool {
	if value == "" {
		return false
	}
	for _, character := range value {
		if character < '0' || character > '9' {
			return false
		}
	}
	return true
}

type bridgeRuntime struct {
	handler      http.Handler
	pairingToken string
	store        *store.Store
}

func newRuntime(databasePath string, adapter, commandAdapter codex.Adapter) (*bridgeRuntime, error) {
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
	commandService := commands.NewService(database, codex.NewCommandExecutor(commandAdapter))
	server := api.NewServer(pairing, adapter, api.WithCommands(commandService), api.WithEvents(broker))
	return &bridgeRuntime{handler: server.Handler(), pairingToken: token, store: database}, nil
}

func (r *bridgeRuntime) Close() error { return r.store.Close() }

func commandAdapterForServe(adapter codex.Adapter, fake bool) codex.Adapter {
	if fake {
		return adapter
	}
	return codex.NewDesktopCommandAdapter(adapter, codex.NewDesktopAppToolsClient())
}

func pairingInvitationURL(advertiseBaseURL, token string) (string, error) {
	invitation := url.URL{Scheme: "codex-remote", Host: "pair"}
	query := invitation.Query()
	query.Set("baseUrl", advertiseBaseURL)
	query.Set("token", token)
	invitation.RawQuery = query.Encode()
	return invitation.String(), nil
}

func pairingOutput(advertiseURL, token string) (string, error) {
	output := fmt.Sprintf("One-time pairing token (expires in 5 minutes): %s\n", token)
	if advertiseURL == "" {
		return output + "Pairing link unavailable; set --advertise-url to a phone-reachable origin.\n", nil
	}
	link, err := pairingInvitationURL(advertiseURL, token)
	if err != nil {
		return "", err
	}
	return output + fmt.Sprintf("Pairing link: %s\n", link), nil
}

func runServe(options serveOptions) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := os.MkdirAll(filepath.Dir(options.data), 0o700); err != nil {
		return err
	}
	var adapter codex.Adapter
	if options.fake {
		adapter = codex.NewFakeAdapter()
	} else {
		realAdapter, appServer, err := startRealAdapter(ctx, options, startAppServerProcess)
		if err != nil {
			return err
		}
		defer appServer.Close()
		adapter = realAdapter
	}
	runtime, err := newRuntime(options.data, adapter, commandAdapterForServe(adapter, options.fake))
	if err != nil {
		return err
	}
	defer runtime.Close()
	pairingDetails, err := pairingOutput(options.advertiseURL, runtime.pairingToken)
	if err != nil {
		return err
	}

	server := &http.Server{
		Addr: options.listen, Handler: runtime.handler,
		ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()

	fmt.Printf("Bridge listening on http://%s\n", options.listen)
	fmt.Print(pairingDetails)
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
	err := runApplication(os.Args[1:], applicationActions{
		desktop: func() error { return desktop.RunApplication(version) },
		serve:   runServe,
		version: func() error { fmt.Println(versionText(version)); return nil },
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

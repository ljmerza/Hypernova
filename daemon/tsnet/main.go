// hypernova-tsnet: Tailscale built into Hypernova's computer side (docs/PROTOCOL.md, "Tailscale
// built in"). The daemon starts and supervises this helper; it joins the user's tailnet as its own
// node ("hypernova-<host>") with tsnet, so the user never installs the Tailscale app, and it serves
// the daemon's phone API (plain HTTP on 127.0.0.1) over the tailnet with HTTPS. No TCP service of
// this helper is reachable from outside the tailnet: tsnet's listeners exist only inside it (what a
// Tailscale node has on the outside is WireGuard's UDP and its port mapping, as always). The state
// (the node key) lives in the daemon's support folder.
//
// The daemon talks to it in JSON lines: status on stdout whenever something changes (the sign-in
// link, the tailnet name, whether it's serving), and one-word commands on stdin ("login",
// "logout", "status"). Everything else is flags, so the Linux daemon can run it the same way.
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/skip2/go-qrcode"
	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// status is the one message the helper writes, whole, each time something in it changes.
type status struct {
	T string `json:"t"`
	// NeedsLogin, Starting, Running, Stopped, NoState (as Tailscale names them).
	State    string `json:"state"`
	LoginURL string `json:"loginUrl,omitempty"`
	// The sign-in link as a QR code for a terminal (half-block characters), while one is needed.
	QR      string   `json:"qr,omitempty"`
	DNSName string   `json:"dnsName,omitempty"`
	IPs     []string `json:"ips,omitempty"`
	// The account the node signed in with ("you@example.com"), so the phone can be told whom to sign in as.
	Account string `json:"account,omitempty"`
	Tailnet string `json:"tailnet,omitempty"`
	// The coordination server; empty for tailscale.com.
	ControlURL string `json:"controlUrl,omitempty"`
	// The tailnet can issue HTTPS certificates for this node's name.
	HTTPS bool `json:"https"`
	// What the phone API is served on, once it is: "https://<name>:<port>".
	Listening string `json:"listening,omitempty"`
	// "tailscale" (a certificate from the tailnet) or "pinned" (the daemon's own, which the phone pins).
	TLS   string `json:"tls,omitempty"`
	Error string `json:"error,omitempty"`
}

type helper struct {
	srv     *tsnet.Server
	lc      *local.Client
	port    int
	target  string
	certPEM string
	keyPEM  string

	mu      sync.Mutex
	st      status
	out     *json.Encoder
	serving *http.Server
}

func main() {
	state := flag.String("state", "", "the folder for the node's state (0700; required)")
	hostname := flag.String("hostname", "hypernova", "the node's name on the tailnet")
	port := flag.Int("port", 47321, "the tailnet port to serve the phone API on")
	target := flag.String("target", "127.0.0.1:47321", "the daemon's phone API (plain HTTP on loopback)")
	control := flag.String("control", "", "the coordination server (empty: tailscale.com; a Headscale URL for self-hosters)")
	authKeyEnv := flag.String("authkey-env", "", "the name of an environment variable holding a pre-auth key (tests, Headscale); never the key itself")
	cert := flag.String("cert", "", "the daemon's own certificate (PEM), served when the tailnet has no HTTPS certificates")
	key := flag.String("key", "", "its private key (PEM)")
	flag.Parse()
	if *state == "" {
		fmt.Fprintln(os.Stderr, "hypernova-tsnet: --state is required")
		os.Exit(2)
	}
	if err := os.MkdirAll(*state, 0o700); err != nil {
		fmt.Fprintln(os.Stderr, "hypernova-tsnet:", err)
		os.Exit(1)
	}
	// The key comes through the environment, so it never shows in a process list; read once and dropped.
	authKey := ""
	if *authKeyEnv != "" {
		authKey = os.Getenv(*authKeyEnv)
		os.Unsetenv(*authKeyEnv)
	}
	// Tailscale's own logs go to stderr; the daemon keeps them in its log at debug level.
	log.SetOutput(os.Stderr)
	h := &helper{
		srv: &tsnet.Server{
			Dir:        *state,
			Hostname:   *hostname,
			ControlURL: *control,
			AuthKey:    authKey,
			Logf:       func(string, ...any) {}, // the backend's chatter: silent
			UserLogf:   func(f string, a ...any) { fmt.Fprintf(os.Stderr, f+"\n", a...) },
		},
		port:    *port,
		target:  *target,
		certPEM: *cert,
		keyPEM:  *key,
		out:     json.NewEncoder(os.Stdout),
	}
	h.st = status{T: "status", State: "Starting", ControlURL: *control}
	h.emit()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go h.stdinCommands(ctx, cancel)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() { <-sig; cancel() }()

	if err := h.srv.Start(); err != nil {
		h.fail("start: " + err.Error())
		os.Exit(1)
	}
	lc, err := h.srv.LocalClient()
	if err != nil {
		h.fail("local client: " + err.Error())
		os.Exit(1)
	}
	h.lc = lc
	go h.watch(ctx)
	<-ctx.Done()
	h.stopServing()
	h.srv.Close()
	h.set(func(s *status) { s.State = "Stopped" })
}

// watch follows the node's state and tells the daemon about every change: the sign-in link while
// it needs one, the name and addresses once it runs; it serves while the node runs and stops
// serving the moment it doesn't (a logout, a key that expired), so a later sign-in can serve again.
func (h *helper) watch(ctx context.Context) {
	for ctx.Err() == nil {
		w, err := h.lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
		if err != nil {
			h.fail("watch: " + err.Error())
			time.Sleep(2 * time.Second)
			continue
		}
		for {
			n, err := w.Next()
			if err != nil {
				break
			}
			if n.BrowseToURL != nil {
				qr := ""
				if q, err := qrcode.New(*n.BrowseToURL, qrcode.Medium); err == nil {
					qr = q.ToSmallString(false)
				}
				h.set(func(s *status) { s.LoginURL = *n.BrowseToURL; s.QR = qr })
			}
			if n.State != nil {
				st := n.State.String()
				h.set(func(s *status) {
					s.State = st
					if st != "NeedsLogin" {
						s.LoginURL = ""
						s.QR = ""
					}
				})
				if *n.State == ipn.Running {
					h.refresh(ctx)
					h.serve(ctx)
				} else {
					h.stopServing()
					if *n.State == ipn.NeedsLogin && n.BrowseToURL == nil && h.srv.AuthKey == "" {
						// A node that was logged out (or never in) must ask for a link: tsnet starts the
						// interactive login itself only on Up(), which we don't call, so ask here. With
						// a pre-auth key the backend is signing in by itself.
						go h.lc.StartLoginInteractive(ctx)
					}
				}
			}
		}
		w.Close()
	}
}

// refresh reads the node's name, addresses, account and HTTPS ability from its status.
func (h *helper) refresh(ctx context.Context) {
	st, err := h.lc.StatusWithoutPeers(ctx)
	if err != nil || st.Self == nil {
		return
	}
	var ips []string
	for _, ip := range st.TailscaleIPs {
		ips = append(ips, ip.String())
	}
	account := ""
	if st.CurrentTailnet != nil {
		account = st.CurrentTailnet.Name
	}
	if u, ok := st.User[st.Self.UserID]; ok && u.LoginName != "" {
		account = u.LoginName
	}
	tailnet := ""
	if st.CurrentTailnet != nil {
		tailnet = st.CurrentTailnet.MagicDNSSuffix
	}
	h.set(func(s *status) {
		s.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
		s.IPs = ips
		s.Account = account
		s.Tailnet = tailnet
		s.HTTPS = len(st.CertDomains) > 0
	})
}

// serve puts the daemon's phone API on the tailnet: HTTPS with the tailnet's certificate for this
// node's name when the tailnet issues them, else with the daemon's own certificate (the one the
// phone pins from the pairing code) on the tailnet address. Once per run of the node.
func (h *helper) serve(ctx context.Context) {
	h.mu.Lock()
	if h.serving != nil {
		h.mu.Unlock()
		return
	}
	https := h.st.HTTPS
	name := h.st.DNSName
	h.mu.Unlock()

	addr := fmt.Sprintf(":%d", h.port)
	var ln net.Listener
	var err error
	mode := "tailscale"
	host := name
	if https {
		ln, err = h.srv.ListenTLS("tcp", addr)
	} else {
		mode = "pinned"
		var cert tls.Certificate
		cert, err = tls.LoadX509KeyPair(h.certPEM, h.keyPEM)
		if err == nil {
			var plain net.Listener
			plain, err = h.srv.Listen("tcp", addr)
			if err == nil {
				ln = tls.NewListener(plain, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
			}
		}
		// Without HTTPS certificates the name's certificate can't be trusted, so the phone goes by
		// the address and the pin.
		if ip4, _ := h.srv.TailscaleIPs(); ip4.IsValid() {
			host = ip4.String()
		}
	}
	if err != nil {
		h.fail("listen: " + err.Error())
		return
	}
	who := func(ctx context.Context, remote string) (login, display string) {
		if w, err := h.lc.WhoIs(ctx, remote); err == nil && w.UserProfile != nil {
			return w.UserProfile.LoginName, w.UserProfile.DisplayName
		}
		return "tailnet", ""
	}
	h.serveOn(ctx, ln, host, mode, who)
}

// serveOn runs the reverse proxy on ln (a tailnet listener; tests hand in a loopback one) and
// reports the address; a second call while one serves closes the new listener and keeps the first.
func (h *helper) serveOn(ctx context.Context, ln net.Listener, host, mode string, who func(ctx context.Context, remote string) (login, display string)) {
	server := &http.Server{Handler: proxyHandler(h.target, who), ReadHeaderTimeout: 15 * time.Second}
	h.mu.Lock()
	if h.serving != nil {
		h.mu.Unlock()
		ln.Close()
		return
	}
	h.serving = server
	h.mu.Unlock()
	h.set(func(s *status) {
		s.Listening = fmt.Sprintf("https://%s:%d", host, h.port)
		s.TLS = mode
		s.Error = ""
	})
	go func() {
		err := server.Serve(ln)
		h.mu.Lock()
		mine := h.serving == server
		if mine {
			h.serving = nil
		}
		h.mu.Unlock()
		if mine && err != nil && !errors.Is(err, http.ErrServerClosed) && ctx.Err() == nil {
			h.fail("serve: " + err.Error())
		}
	}()
}

// stopServing closes the server and its tailnet listener (a logout, the node leaving Running, the
// end), so the next Running can listen on the same port again.
func (h *helper) stopServing() {
	h.mu.Lock()
	server := h.serving
	h.serving = nil
	h.mu.Unlock()
	if server == nil {
		return
	}
	server.Close()
	h.set(func(s *status) { s.Listening = ""; s.TLS = "" })
}

// proxyHandler is the reverse proxy from the tailnet to the daemon's loopback API. It adds what
// tailscale serve adds and the daemon reads (who is calling, so a tailnet request never counts as
// the Mac's own loopback; that TLS ended here), and drops any such header a client sent itself.
// The Host the phone used goes through unchanged, so the daemon builds the links it hands out
// (a preview's, the checklist's) from it.
func proxyHandler(target string, who func(ctx context.Context, remote string) (login, display string)) http.Handler {
	targetURL, _ := url.Parse("http://" + target)
	proxy := &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(targetURL)
			pr.Out.Host = pr.In.Host
			// Nothing a client claims about itself survives.
			for _, hname := range []string{"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "Forwarded", "Tailscale-User-Login", "Tailscale-User-Name", "Tailscale-User-Profile-Pic"} {
				pr.Out.Header.Del(hname)
			}
			peer := pr.In.RemoteAddr
			if ap, err := netip.ParseAddrPort(peer); err == nil {
				peer = ap.Addr().String()
			}
			pr.Out.Header.Set("X-Forwarded-For", peer)
			pr.Out.Header.Set("X-Forwarded-Proto", "https")
			login, display := who(pr.In.Context(), pr.In.RemoteAddr)
			pr.Out.Header.Set("Tailscale-User-Login", login)
			if display != "" {
				pr.Out.Header.Set("Tailscale-User-Name", display)
			}
		},
		// The daemon isn't up (yet): the phone tells "not running" from a bare 502, as behind tailscale serve.
		ErrorHandler:  func(w http.ResponseWriter, r *http.Request, err error) { w.WriteHeader(http.StatusBadGateway) },
		FlushInterval: -1, // streams (the live feed, read aloud) must not buffer
	}
	return proxy
}

// stdinCommands reads one-word commands from the daemon. EOF (the daemon went) stops the helper.
func (h *helper) stdinCommands(ctx context.Context, cancel context.CancelFunc) {
	dec := json.NewDecoder(os.Stdin)
	for {
		var cmd struct {
			C string `json:"c"`
		}
		if err := dec.Decode(&cmd); err != nil {
			cancel()
			return
		}
		switch cmd.C {
		case "status":
			h.emit()
		case "login":
			if h.lc != nil {
				go h.lc.StartLoginInteractive(ctx)
			}
		case "logout":
			// Another account, or a fresh start: the node key is dropped and a new sign-in link comes.
			if h.lc != nil {
				go func() {
					h.stopServing()
					h.lc.Logout(ctx)
					h.set(func(s *status) { s.DNSName = ""; s.IPs = nil; s.Account = "" })
					h.lc.StartLoginInteractive(ctx)
				}()
			}
		}
	}
}

func (h *helper) set(f func(*status)) {
	h.mu.Lock()
	before, _ := json.Marshal(h.st)
	f(&h.st)
	after, _ := json.Marshal(h.st)
	changed := string(before) != string(after)
	h.mu.Unlock()
	if changed {
		h.emit()
	}
}

func (h *helper) fail(msg string) {
	fmt.Fprintln(os.Stderr, "hypernova-tsnet:", msg)
	h.set(func(s *status) { s.Error = msg })
}

func (h *helper) emit() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.out.Encode(h.st)
}

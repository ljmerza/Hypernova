// Tailscale built into the Hypernova app (docs/PROTOCOL.md, "Tailscale built in"): a tsnet node in
// userspace, bound into an AAR with gomobile. No VpnService, so the user's own VPN keeps working,
// and no Tailscale app to install: the user signs in once, in the browser, with the link this
// package hands to the app.
//
// The app's traffic reaches the tailnet through a small HTTP CONNECT proxy on loopback (OkHttp,
// and a WebView, can both use one). It takes a secret the app alone knows, tunnels only to the
// destinations the app allowed (the paired computer), and only to a peer of the tailnet (never
// out to the internet through the phone), so another app on the phone can't borrow it.
package tsnet

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"runtime"
	"strings"
	"sync"
	"time"

	"tailscale.com/client/local"
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu       sync.Mutex
	srv      *tsnet.Server
	lc       *local.Client
	cancel   context.CancelFunc
	st       = status{State: "Stopped"}
	proxyLn  net.Listener
	allowed  = map[string]bool{}
	listener Listener
	// gen counts Starts: a goroutine of an earlier node (its watcher, its proxy coming up) finds
	// its number stale and changes nothing, so a Stop followed by a Start can't be confused.
	gen uint64
	// started is what the running node was started with, for Logout to start it again.
	started startArgs
)

type startArgs struct{ stateDir, hostname, controlURL, authKey string }

type status struct {
	// Stopped, Starting, NeedsLogin, NeedsMachineAuth, Running, NoState (as Tailscale names them).
	State    string   `json:"state"`
	LoginURL string   `json:"loginUrl,omitempty"`
	DNSName  string   `json:"dnsName,omitempty"`
	IPs      []string `json:"ips,omitempty"`
	Account  string   `json:"account,omitempty"`
	Tailnet  string   `json:"tailnet,omitempty"`
	HTTPS    bool     `json:"https"`
	// The loopback proxy's port and the secret it takes (Proxy-Authorization: Basic hypernova:<token>), once the node runs.
	ProxyPort  int    `json:"proxyPort,omitempty"`
	ProxyToken string `json:"proxyToken,omitempty"`
	Error      string `json:"error,omitempty"`
}

// Listener hears every change of status (the app's Kotlin side implements it).
type Listener interface {
	OnStatus(json string)
}

// SetListener registers the one listener for status changes.
func SetListener(l Listener) {
	mu.Lock()
	listener = l
	mu.Unlock()
}

// Start brings the node up in stateDir (app-private, one folder per control server), named
// hostname on the tailnet. controlURL is empty for tailscale.com, a Headscale URL for
// self-hosters; authKey is for tests. Idempotent.
func Start(stateDir, hostname, controlURL, authKey string) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	// An app has no HOME or cache dir Go knows of, and Tailscale's log policy panics without a
	// place for its state ("no safe place found to store log state"): everything goes under the
	// state folder. And no log uploads to Tailscale from a user's phone.
	os.Setenv("HOME", stateDir)
	os.Setenv("XDG_CACHE_HOME", stateDir)
	envknob.Setenv("TS_LOGS_DIR", stateDir)
	envknob.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	s := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		Logf:       func(string, ...any) {},
		UserLogf:   func(f string, a ...any) { fmt.Printf("tsnet: "+f+"\n", a...) },
	}
	if err := s.Start(); err != nil {
		return err
	}
	c, err := s.LocalClient()
	if err != nil {
		s.Close()
		return err
	}
	srv, lc = s, c
	started = startArgs{stateDir, hostname, controlURL, authKey}
	gen++
	g := gen
	ctx, cf := context.WithCancel(context.Background())
	cancel = cf
	setLocked(func(x *status) { *x = status{State: "Starting"} })
	go watch(ctx, g, s, c)
	return nil
}

// Stop closes the node and its proxy. The state on disk stays, so the next Start needs no sign-in.
func Stop() { stop(true) }

// stop closes the node; say false: it starts again at once (Logout), so the app hears Starting, not Stopped.
func stop(say bool) {
	mu.Lock()
	s, cf, ln := srv, cancel, proxyLn
	srv, lc, cancel, proxyLn = nil, nil, nil, nil
	gen++ // whatever the old node still says is stale from here on
	g := gen
	mu.Unlock()
	if cf != nil {
		cf()
	}
	if ln != nil {
		ln.Close()
	}
	if s != nil {
		s.Close()
	}
	next := "Stopped"
	if !say {
		next = "Starting"
	}
	setGen(g, func(x *status) { *x = status{State: next} })
}

// Status is the node's state as JSON (see status).
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	b, _ := json.Marshal(st)
	return string(b)
}

// Login asks for a (new) sign-in link; it arrives in the next status.
func Login() error {
	mu.Lock()
	c := lc
	mu.Unlock()
	if c == nil {
		return errors.New("not started")
	}
	return c.StartLoginInteractive(context.Background())
}

// Logout signs the node out (another account, or a fresh start) and starts it again, waiting for
// a sign-in. Tailscale's logout deletes the node's profile, and the profile that follows starts
// from Tailscale's defaults: tailscale.com rather than the app's control server, no name, not
// running. So the node starts over as the app started it, and asks for a sign-in link there.
func Logout() error {
	mu.Lock()
	c, args := lc, started
	mu.Unlock()
	if c == nil {
		return errors.New("not started")
	}
	// What the old node says while it signs out (Stopped, NoState…) is stale from here: the app
	// hears Starting, then the new node's sign-in.
	mu.Lock()
	gen++
	g := gen
	mu.Unlock()
	setGen(g, func(x *status) { *x = status{State: "Starting"} })
	ctx, cf := context.WithTimeout(context.Background(), 10*time.Second)
	defer cf()
	err := c.Logout(ctx)
	stop(false)
	if serr := Start(args.stateDir, args.hostname, args.controlURL, args.authKey); serr != nil {
		return serr
	}
	return err
}

// SetAllowed replaces the destinations the proxy may tunnel to: "name:port" or "address:port"
// (an IPv6 address in brackets), comma-separated; the app names its paired computer. Case
// doesn't matter.
func SetAllowed(csv string) {
	next := map[string]bool{}
	for _, hp := range strings.Split(csv, ",") {
		if hp = strings.ToLower(strings.TrimSpace(hp)); hp != "" {
			next[hp] = true
		}
	}
	mu.Lock()
	allowed = next
	mu.Unlock()
}

// Allow adds one destination to those the proxy may tunnel to (a pairing under way, next to the
// current computer's); SetAllowed from the router replaces the list afterwards.
func Allow(hostPort string) {
	hp := strings.ToLower(strings.TrimSpace(hostPort))
	if hp == "" {
		return
	}
	mu.Lock()
	allowed[hp] = true
	mu.Unlock()
}

// ProxyPort is the loopback proxy's port, or 0 before the node runs.
func ProxyPort() int {
	mu.Lock()
	defer mu.Unlock()
	return st.ProxyPort
}

// Dump is every goroutine's stack, for a debug build to log when the node seems stuck.
func Dump() string {
	buf := make([]byte, 1<<20)
	n := runtime.Stack(buf, true)
	return string(buf[:n])
}

// Ping dials hostPort through the tailnet and says how long it took, in milliseconds (-1: failed).
func Ping(hostPort string, timeoutMs int) int {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return -1
	}
	ctx, cf := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cf()
	t0 := time.Now()
	c, err := s.Dial(ctx, "tcp", hostPort)
	if err != nil {
		return -1
	}
	c.Close()
	return int(time.Since(t0).Milliseconds())
}

func watch(ctx context.Context, g uint64, s *tsnet.Server, c *local.Client) {
	set := func(f func(*status)) { setGen(g, f) }
	for ctx.Err() == nil {
		w, err := c.WatchIPNBus(ctx, ipn.NotifyInitialState)
		if err != nil {
			set(func(x *status) { x.Error = err.Error() })
			time.Sleep(2 * time.Second)
			continue
		}
		for {
			n, err := w.Next()
			if err != nil {
				break
			}
			if n.BrowseToURL != nil {
				u := *n.BrowseToURL
				set(func(x *status) { x.LoginURL = u })
			}
			if n.ErrMessage != nil {
				// Tailscale's own words for a node it stopped (a tailnet that needs flow logs, which
				// this node doesn't send, say): the app shows them rather than waiting in silence.
				m := *n.ErrMessage
				set(func(x *status) { x.Error = m })
			}
			if n.State != nil {
				name := n.State.String()
				set(func(x *status) {
					x.State = name
					if name != "NeedsLogin" {
						x.LoginURL = ""
					}
				})
				switch *n.State {
				case ipn.Running:
					refresh(ctx, g, c)
					startProxy(g, s, c)
				case ipn.Starting, ipn.NeedsMachineAuth:
					// Signed in: the account and this node's name are known from here on, so the app
					// can say who is waiting for approval, or connecting.
					refresh(ctx, g, c)
				case ipn.NeedsLogin:
					if n.BrowseToURL == nil && s.AuthKey == "" {
						go askForLink(ctx, g, c)
					}
				}
			}
		}
		w.Close()
	}
}

// askForLink asks control for a sign-in link when, after a moment, there still is none. tsnet asks
// itself when it starts at NeedsLogin, and a link may be on its way: asking again while one is in
// flight would start the sign-in over with a new key, and the link the user then opens would
// authorize a key this node no longer uses (the browser says done, the app keeps asking).
func askForLink(ctx context.Context, g uint64, c *local.Client) {
	select {
	case <-ctx.Done():
		return
	case <-time.After(linkWait):
	}
	if !current(g) {
		return
	}
	st, err := c.StatusWithoutPeers(ctx)
	if err != nil || st.BackendState != "NeedsLogin" || st.AuthURL != "" {
		return
	}
	c.StartLoginInteractive(ctx)
}

// linkWait is how long a sign-in link gets to arrive by itself before the node asks for one.
var linkWait = 3 * time.Second

// current reports whether g is still the running node's generation.
func current(g uint64) bool {
	mu.Lock()
	defer mu.Unlock()
	return g == gen
}

func refresh(ctx context.Context, g uint64, c *local.Client) {
	s, err := c.StatusWithoutPeers(ctx)
	if err != nil || s.Self == nil {
		return
	}
	set := func(f func(*status)) { setGen(g, f) }
	var ips []string
	for _, ip := range s.TailscaleIPs {
		ips = append(ips, ip.String())
	}
	account, tailnet := "", ""
	if s.CurrentTailnet != nil {
		account, tailnet = s.CurrentTailnet.Name, s.CurrentTailnet.MagicDNSSuffix
	}
	if u, ok := s.User[s.Self.UserID]; ok && u.LoginName != "" {
		account = u.LoginName
	}
	set(func(x *status) {
		x.DNSName = strings.TrimSuffix(s.Self.DNSName, ".")
		x.IPs = ips
		x.Account = account
		x.Tailnet = tailnet
		x.HTTPS = len(s.CertDomains) > 0
		x.Error = ""
	})
}

// ── The loopback proxy ─────────────────────────────────────────────────────

// At most this many tunnels at once: a phone needs a handful (requests, uploads, the feed, a terminal).
const maxTunnels = 64

// proxyRules is what one CONNECT is checked against; tests hand in their own.
type proxyRules struct {
	// The secret clients must send: Proxy-Authorization: Basic base64("hypernova:<token>").
	token string
	// The destinations ("host:port", lowercase) the app allowed.
	allowed func(hostPort string) bool
	// Dials a tailnet peer by name or address; an error when it isn't one.
	dial func(ctx context.Context, host, port string) (net.Conn, error)
	// Tunnels under way, for the cap.
	slots chan struct{}
}

// startProxy opens the loopback CONNECT proxy once per node, with a fresh secret.
func startProxy(g uint64, s *tsnet.Server, c *local.Client) {
	set := func(f func(*status)) { setGen(g, f) }
	mu.Lock()
	if proxyLn != nil || srv != s || gen != g {
		// Already open, or this node was stopped while it was coming up.
		mu.Unlock()
		return
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		mu.Unlock()
		set(func(x *status) { x.Error = "proxy: " + err.Error() })
		return
	}
	proxyLn = ln
	mu.Unlock()
	var secret [16]byte
	rand.Read(secret[:])
	token := hex.EncodeToString(secret[:])
	rules := proxyRules{
		token: token,
		allowed: func(hp string) bool {
			mu.Lock()
			defer mu.Unlock()
			return allowed[hp]
		},
		dial:  func(ctx context.Context, host, port string) (net.Conn, error) { return dialPeer(ctx, s, c, host, port) },
		slots: make(chan struct{}, maxTunnels),
	}
	port := ln.Addr().(*net.TCPAddr).Port
	set(func(x *status) { x.ProxyPort = port; x.ProxyToken = token })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go serveConnect(conn, rules)
		}
	}()
}

// dialPeer dials host:port only when host is a peer of the tailnet: a tailnet address the node
// knows, or a peer's MagicDNS name, resolved from the node's own peer list to that peer's
// address. Never system DNS, never the internet.
func dialPeer(ctx context.Context, s *tsnet.Server, c *local.Client, host, port string) (net.Conn, error) {
	addr, err := peerAddr(ctx, c, host)
	if err != nil {
		return nil, err
	}
	return s.Dial(ctx, "tcp", net.JoinHostPort(addr.String(), port))
}

func peerAddr(ctx context.Context, c *local.Client, host string) (netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		if !tailnetAddr(ip) {
			return netip.Addr{}, errors.New("not a tailnet address")
		}
		if _, err := c.WhoIs(ctx, ip.String()); err != nil {
			return netip.Addr{}, errors.New("not a peer")
		}
		return ip, nil
	}
	stat, err := c.Status(ctx)
	if err != nil {
		return netip.Addr{}, err
	}
	want := strings.ToLower(strings.TrimSuffix(host, "."))
	for _, p := range stat.Peer {
		if strings.ToLower(strings.TrimSuffix(p.DNSName, ".")) == want && len(p.TailscaleIPs) > 0 {
			return p.TailscaleIPs[0], nil
		}
	}
	return netip.Addr{}, errors.New("no such peer")
}

var (
	cgnat = netip.MustParsePrefix("100.64.0.0/10")
	ula   = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
)

func tailnetAddr(ip netip.Addr) bool { return cgnat.Contains(ip) || ula.Contains(ip) }

// serveConnect serves one CONNECT: the secret, the allowlist, the cap, then a peer dial and
// bytes both ways. Anything else gets a short refusal and the connection closed.
func serveConnect(c net.Conn, rules proxyRules) {
	defer c.Close()
	c.SetReadDeadline(time.Now().Add(15 * time.Second))
	r := bufio.NewReader(c)
	req, err := http.ReadRequest(r)
	if err != nil {
		return
	}
	if req.Method != http.MethodConnect {
		io.WriteString(c, "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
		return
	}
	if !tokenOK(req.Header.Get("Proxy-Authorization"), rules.token) {
		io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"hypernova\"\r\nConnection: close\r\n\r\n")
		return
	}
	dst := strings.ToLower(req.Host)
	host, port, err := net.SplitHostPort(dst)
	if err != nil || !rules.allowed(dst) {
		io.WriteString(c, "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
		return
	}
	select {
	case rules.slots <- struct{}{}:
		defer func() { <-rules.slots }()
	default:
		io.WriteString(c, "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n")
		return
	}
	ctx, cf := context.WithTimeout(context.Background(), 20*time.Second)
	up, err := rules.dial(ctx, host, port)
	cf()
	if err != nil {
		io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		return
	}
	defer up.Close()
	c.SetReadDeadline(time.Time{})
	if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() { io.Copy(up, r); done <- struct{}{} }()
	go func() { io.Copy(c, up); done <- struct{}{} }()
	<-done
}

// tokenOK checks "Basic base64(hypernova:<token>)" in constant time.
func tokenOK(header, token string) bool {
	const prefix = "Basic "
	if token == "" || !strings.HasPrefix(header, prefix) {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(header[len(prefix):]))
	if err != nil {
		return false
	}
	want := "hypernova:" + token
	return subtle.ConstantTimeCompare(raw, []byte(want)) == 1
}

func set(f func(*status)) {
	mu.Lock()
	g := gen
	mu.Unlock()
	setGen(g, f)
}

// setGen applies f to the status when g is the current node's generation; a stale goroutine's
// news is dropped. The listener hears every change.
func setGen(g uint64, f func(*status)) {
	mu.Lock()
	if g != gen {
		mu.Unlock()
		return
	}
	changed := setLocked(f)
	l := listener
	j, _ := json.Marshal(st)
	mu.Unlock()
	if changed && l != nil {
		l.OnStatus(string(j))
	}
}

func setLocked(f func(*status)) bool {
	before, _ := json.Marshal(st)
	f(&st)
	after, _ := json.Marshal(st)
	return string(before) != string(after)
}

// Android denies apps the netlink calls Go's net.Interfaces uses ("netlinkrib: permission
// denied"), so the app reads the interfaces with Java and hands them over here, at start and on
// every network change; the node's network monitor asks this getter instead of the kernel.
var (
	ifMu   sync.Mutex
	ifList []netmon.Interface
	ifSet  bool
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifMu.Lock()
		defer ifMu.Unlock()
		if !ifSet {
			return nil, errors.New("tsnet: the app hasn't given the interfaces yet")
		}
		return append([]netmon.Interface(nil), ifList...), nil
	})
}

type ifaceJSON struct {
	Name     string   `json:"name"`
	Index    int      `json:"index"`
	MTU      int      `json:"mtu"`
	Up       bool     `json:"up"`
	Loopback bool     `json:"loopback"`
	HW       string   `json:"hw"`
	Addrs    []string `json:"addrs"` // "10.0.2.16/24", "fe80::1/64"
}

// SetInterfaces takes the phone's network interfaces as JSON (see ifaceJSON), from Java's
// NetworkInterface list, which Android lets an app read.
func SetInterfaces(list string) error {
	var in []ifaceJSON
	if err := json.Unmarshal([]byte(list), &in); err != nil {
		return err
	}
	var out []netmon.Interface
	for _, i := range in {
		ni := &net.Interface{Index: i.Index, MTU: i.MTU, Name: i.Name}
		if hw, err := net.ParseMAC(i.HW); err == nil {
			ni.HardwareAddr = hw
		}
		if i.Up {
			ni.Flags |= net.FlagUp | net.FlagRunning
		}
		if i.Loopback {
			ni.Flags |= net.FlagLoopback
		} else {
			ni.Flags |= net.FlagBroadcast | net.FlagMulticast
		}
		var addrs []net.Addr
		for _, a := range i.Addrs {
			if ip, ipnet, err := net.ParseCIDR(a); err == nil {
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: ipnet.Mask})
			}
		}
		if addrs == nil {
			addrs = []net.Addr{}
		}
		out = append(out, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	ifMu.Lock()
	ifList, ifSet = out, true
	ifMu.Unlock()
	return nil
}

// SetDefaultRoute names the interface the phone's default network uses right now (and its
// gateway, which may be empty), from ConnectivityManager; "" when there is no network.
func SetDefaultRoute(ifName, gateway string) { setDefaultRoute(ifName, gateway) }

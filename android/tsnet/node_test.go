package tsnet

import (
	"bufio"
	"context"
	"encoding/base64"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"
)

// A proxy with a fake dialer: "mac.tailnet:47321" is a peer that echoes; anything else isn't.
func testRules(t *testing.T, dialed *[]string) proxyRules {
	t.Helper()
	return proxyRules{
		token: "secret-token",
		allowed: func(hp string) bool {
			return hp == "mac.tailnet:47321" || hp == "100.64.0.2:47321" || hp == "[fd7a:115c:a1e0::2]:47321"
		},
		dial: func(ctx context.Context, host, port string) (net.Conn, error) {
			*dialed = append(*dialed, net.JoinHostPort(host, port))
			if host != "mac.tailnet" && host != "100.64.0.2" {
				return nil, errors.New("no such peer")
			}
			a, b := net.Pipe()
			go func() {
				defer b.Close()
				io.Copy(b, b) // echo
			}()
			return a, nil
		},
		slots: make(chan struct{}, 2),
	}
}

func connectVia(t *testing.T, rules proxyRules, target, auth string) (*bufio.Reader, net.Conn, *http.Response) {
	t.Helper()
	client, server := net.Pipe()
	go serveConnect(server, rules)
	req := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
	if auth != "" {
		req += "Proxy-Authorization: " + auth + "\r\n"
	}
	req += "\r\n"
	client.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(client, req); err != nil {
		t.Fatal(err)
	}
	r := bufio.NewReader(client)
	res, err := http.ReadResponse(r, nil)
	if err != nil {
		t.Fatal(err)
	}
	return r, client, res
}

func basic(token string) string {
	return "Basic " + base64.StdEncoding.EncodeToString([]byte("hypernova:"+token))
}

func TestConnectTunnelsAnAllowedPeerWithTheSecret(t *testing.T) {
	var dialed []string
	r, c, res := connectVia(t, testRules(t, &dialed), "Mac.Tailnet:47321", basic("secret-token"))
	defer c.Close()
	if res.StatusCode != 200 {
		t.Fatalf("status %d, want 200", res.StatusCode)
	}
	if len(dialed) != 1 || dialed[0] != "mac.tailnet:47321" {
		t.Fatalf("dialed %v (the name lowercased, by the peer dialer only)", dialed)
	}
	// Bytes go both ways.
	io.WriteString(c, "hello")
	buf := make([]byte, 5)
	if _, err := io.ReadFull(r, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("echo: %q %v", buf, err)
	}
}

func TestConnectRefusesWithoutTheSecret(t *testing.T) {
	var dialed []string
	rules := testRules(t, &dialed)
	for _, auth := range []string{"", "Basic " + base64.StdEncoding.EncodeToString([]byte("hypernova:wrong")), "Bearer secret-token", "Basic not-base64!"} {
		_, c, res := connectVia(t, rules, "mac.tailnet:47321", auth)
		c.Close()
		if res.StatusCode != 407 {
			t.Fatalf("auth %q: status %d, want 407", auth, res.StatusCode)
		}
	}
	if len(dialed) != 0 {
		t.Fatalf("dialed %v without the secret", dialed)
	}
	// No token at all means nothing gets in (the proxy isn't up without one).
	if tokenOK(basic(""), "") {
		t.Fatal("an empty token must never match")
	}
}

func TestConnectRefusesDestinationsNotAllowed(t *testing.T) {
	var dialed []string
	rules := testRules(t, &dialed)
	for _, dst := range []string{"example.com:443", "mac.tailnet:22", "100.64.0.9:47321", "nohost"} {
		_, c, res := connectVia(t, rules, dst, basic("secret-token"))
		c.Close()
		if res.StatusCode != 403 {
			t.Fatalf("%s: status %d, want 403", dst, res.StatusCode)
		}
	}
	if len(dialed) != 0 {
		t.Fatalf("dialed %v although not allowed", dialed)
	}
}

func TestConnectAnswers502WhenTheDestinationIsNoPeer(t *testing.T) {
	// Allowed by the app, but the dialer (which only knows tailnet peers) says no: 502, nothing else tried.
	var dialed []string
	_, c, res := connectVia(t, testRules(t, &dialed), "[fd7a:115c:a1e0::2]:47321", basic("secret-token"))
	c.Close()
	if res.StatusCode != 502 {
		t.Fatalf("status %d, want 502", res.StatusCode)
	}
	if len(dialed) != 1 || dialed[0] != "[fd7a:115c:a1e0::2]:47321" {
		t.Fatalf("dialed %v (brackets kept for IPv6)", dialed)
	}
}

func TestOnlyConnectIsServed(t *testing.T) {
	var dialed []string
	rules := testRules(t, &dialed)
	client, server := net.Pipe()
	go serveConnect(server, rules)
	client.SetDeadline(time.Now().Add(5 * time.Second))
	io.WriteString(client, "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nProxy-Authorization: "+basic("secret-token")+"\r\n\r\n")
	res, err := http.ReadResponse(bufio.NewReader(client), nil)
	if err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 405 {
		t.Fatalf("status %d, want 405", res.StatusCode)
	}
}

func TestTunnelsAreCapped(t *testing.T) {
	var dialed []string
	rules := testRules(t, &dialed) // two slots
	var open []net.Conn
	for i := 0; i < 2; i++ {
		_, c, res := connectVia(t, rules, "mac.tailnet:47321", basic("secret-token"))
		if res.StatusCode != 200 {
			t.Fatalf("tunnel %d: %d", i, res.StatusCode)
		}
		open = append(open, c)
	}
	_, c, res := connectVia(t, rules, "mac.tailnet:47321", basic("secret-token"))
	c.Close()
	if res.StatusCode != 503 {
		t.Fatalf("third tunnel: status %d, want 503", res.StatusCode)
	}
	for _, c := range open {
		c.Close()
	}
}

func TestTailnetAddressesAndAllowlist(t *testing.T) {
	for ip, want := range map[string]bool{"100.64.0.1": true, "100.127.255.254": true, "100.128.0.1": false, "fd7a:115c:a1e0::9": true, "fd00::1": false, "10.0.0.1": false, "1.1.1.1": false} {
		if got := tailnetAddr(mustAddr(t, ip)); got != want {
			t.Errorf("%s: %v, want %v", ip, got, want)
		}
	}
	SetAllowed(" Mac.Tailnet:47321 ,[FD7A:115C:A1E0::2]:47321,, ")
	mu.Lock()
	defer mu.Unlock()
	if !allowed["mac.tailnet:47321"] || !allowed["[fd7a:115c:a1e0::2]:47321"] || len(allowed) != 2 {
		t.Fatalf("allowlist %v", allowed)
	}
	if strings.Contains(strings.Join(keys(allowed), ","), " ") {
		t.Fatal("spaces left in the allowlist")
	}
}

func keys(m map[string]bool) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	return out
}

func mustAddr(t *testing.T, s string) netip.Addr {
	t.Helper()
	ip, err := netip.ParseAddr(s)
	if err != nil {
		t.Fatal(err)
	}
	return ip
}

func TestAStaleGenerationChangesNothing(t *testing.T) {
	mu.Lock()
	gen = 7
	st = status{State: "Running", ProxyPort: 1}
	mu.Unlock()
	// A goroutine of the node before this one (generation 6) reports late: dropped.
	setGen(6, func(x *status) { x.State = "NeedsLogin"; x.ProxyPort = 0 })
	mu.Lock()
	got := st
	mu.Unlock()
	if got.State != "Running" || got.ProxyPort != 1 {
		t.Fatalf("stale news landed: %+v", got)
	}
	// The current one's news lands.
	setGen(7, func(x *status) { x.State = "Stopped" })
	mu.Lock()
	got = st
	mu.Unlock()
	if got.State != "Stopped" {
		t.Fatalf("current news lost: %+v", got)
	}
}

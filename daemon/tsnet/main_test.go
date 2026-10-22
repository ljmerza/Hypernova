package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// The daemon's side: records the headers it received.
func fakeDaemon(t *testing.T) (*httptest.Server, *http.Header) {
	t.Helper()
	var got http.Header
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		got.Set("X-Test-Host", r.Host)
		io.WriteString(w, "ok")
	}))
	t.Cleanup(s.Close)
	return s, &got
}

func newHelper(t *testing.T, target string) (*helper, *strings.Builder) {
	t.Helper()
	out := &strings.Builder{}
	h := &helper{target: target, port: 47321, out: json.NewEncoder(out)}
	h.st = status{T: "status", State: "Running"}
	return h, out
}

func TestProxyAddsItsOwnHeadersAndDropsAClientsClaims(t *testing.T) {
	daemon, got := fakeDaemon(t)
	target := strings.TrimPrefix(daemon.URL, "http://")
	who := func(ctx context.Context, remote string) (string, string) { return "you@example.com", "You" }
	front := httptest.NewServer(proxyHandler(target, who))
	defer front.Close()
	req, _ := http.NewRequest("GET", front.URL+"/v1/health", nil)
	req.Host = "hypernova-mac.tail.ts.net:47321"
	// A phone (or anyone on the tailnet) claiming to be the Mac itself, or someone else.
	req.Header.Set("X-Forwarded-For", "127.0.0.1")
	req.Header.Set("X-Forwarded-Host", "evil")
	req.Header.Set("Tailscale-User-Login", "admin@example.com")
	req.Header.Set("Forwarded", "for=127.0.0.1")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if got.Get("Tailscale-User-Login") != "you@example.com" || got.Get("Tailscale-User-Name") != "You" {
		t.Fatalf("Tailscale-User-*: %v", *got)
	}
	if xff := got.Get("X-Forwarded-For"); xff != "127.0.0.1" || strings.Contains(xff, ",") {
		// The test client is on loopback; what matters is that the client's own value didn't get appended.
		t.Fatalf("X-Forwarded-For %q", xff)
	}
	if got.Get("X-Forwarded-Host") != "" || got.Get("Forwarded") != "" {
		t.Fatalf("claims passed through: %v", *got)
	}
	if got.Get("X-Forwarded-Proto") != "https" {
		t.Fatalf("X-Forwarded-Proto %q", got.Get("X-Forwarded-Proto"))
	}
	if got.Get("X-Test-Host") != "hypernova-mac.tail.ts.net:47321" {
		t.Fatalf("Host %q, want the one the phone sent", got.Get("X-Test-Host"))
	}
}

func TestProxyAnswers502WhenTheDaemonIsDown(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := ln.Addr().String()
	ln.Close() // nothing listens there now
	front := httptest.NewServer(proxyHandler(addr, func(context.Context, string) (string, string) { return "tailnet", "" }))
	defer front.Close()
	res, err := http.Get(front.URL + "/v1/health")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 502 {
		t.Fatalf("status %d, want 502", res.StatusCode)
	}
}

// Logout, then sign in again: the listener must be closed in between, and serving must work again
// (the bug: "listener already open" after a logout).
func TestServeStopsOnLogoutAndServesAgain(t *testing.T) {
	daemon, _ := fakeDaemon(t)
	target := strings.TrimPrefix(daemon.URL, "http://")
	h, out := newHelper(t, target)
	who := func(context.Context, string) (string, string) { return "tailnet", "" }
	ctx := context.Background()

	ln1, _ := net.Listen("tcp", "127.0.0.1:0")
	h.serveOn(ctx, ln1, "100.64.0.2", "pinned", who)
	if h.st.Listening != "https://100.64.0.2:47321" || h.st.TLS != "pinned" {
		t.Fatalf("status after serve: %+v", h.st)
	}
	get := func(addr string) (int, error) {
		c := &http.Client{Timeout: 2 * time.Second}
		res, err := c.Get("http://" + addr + "/v1/health")
		if err != nil {
			return 0, err
		}
		res.Body.Close()
		return res.StatusCode, nil
	}
	if code, err := get(ln1.Addr().String()); err != nil || code != 200 {
		t.Fatalf("first serve: %d %v", code, err)
	}
	// A second serve while one runs keeps the first and closes the new listener.
	ln2, _ := net.Listen("tcp", "127.0.0.1:0")
	h.serveOn(ctx, ln2, "100.64.0.2", "pinned", who)
	if _, err := get(ln2.Addr().String()); err == nil {
		t.Fatal("the second listener should have been closed")
	}

	// Logout.
	h.stopServing()
	if h.st.Listening != "" || h.st.TLS != "" {
		t.Fatalf("status after stop: %+v", h.st)
	}
	if _, err := get(ln1.Addr().String()); err == nil {
		t.Fatal("still answering after stopServing")
	}
	// Sign in again: serve on a fresh listener works.
	ln3, _ := net.Listen("tcp", "127.0.0.1:0")
	h.serveOn(ctx, ln3, "hypernova-mac.tail.ts.net", "tailscale", who)
	if code, err := get(ln3.Addr().String()); err != nil || code != 200 {
		t.Fatalf("serve again: %d %v", code, err)
	}
	if h.st.Listening != "https://hypernova-mac.tail.ts.net:47321" || h.st.TLS != "tailscale" {
		t.Fatalf("status after serving again: %+v", h.st)
	}
	h.stopServing()
	// Every change was reported as a status line.
	lines := strings.Count(out.String(), "\n")
	if lines < 4 {
		t.Fatalf("%d status lines, want one per change:\n%s", lines, out.String())
	}
	_ = tls.VersionTLS12
}

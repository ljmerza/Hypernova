//go:build !android

package tsnet

// Only Android needs telling (go vet on the build machine compiles this file instead).
func setDefaultRoute(ifName, gateway string) {}

//go:build tools

package tsnet

// Keeps golang.org/x/mobile in go.mod: gomobile bind needs it in the module it binds.
import _ "golang.org/x/mobile/bind"

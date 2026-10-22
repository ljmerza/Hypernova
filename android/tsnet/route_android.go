//go:build android

package tsnet

import "tailscale.com/net/netmon"

// netmon's Android build takes the default route from the app instead of netlink.
func setDefaultRoute(ifName, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	if gateway != "" {
		netmon.UpdateLastKnownDefaultGateway(gateway)
	}
}

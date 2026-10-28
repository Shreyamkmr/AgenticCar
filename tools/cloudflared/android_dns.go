// Android DNS patch for cloudflared, added by AgenticCar's tools/cloudflared/build.ps1.
//
// cloudflared is a static Go binary. Go's pure resolver reads /etc/resolv.conf, which
// Android does not have, so every lookup falls back to [::1]:53 and fails. When
// CLOUDFLARED_DNS_SERVERS is set ("ip:port,ip:port"), all lookups in the process are
// sent to those servers instead, in order. Unset, cloudflared behaves exactly as upstream.
package main

import (
	"context"
	"net"
	"os"
	"strings"
)

func init() {
	raw := os.Getenv("CLOUDFLARED_DNS_SERVERS")
	if raw == "" {
		return
	}
	var servers []string
	for _, s := range strings.Split(raw, ",") {
		if s = strings.TrimSpace(s); s != "" {
			servers = append(servers, s)
		}
	}
	if len(servers) == 0 {
		return
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			var lastErr error
			for _, server := range servers {
				conn, err := d.DialContext(ctx, network, server)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

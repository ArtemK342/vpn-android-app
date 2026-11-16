# libopenflux.so — how it is built

Source: https://github.com/p1neappleXpress/OpenFlux tag v0.17.1 (commit f492a1a), GPL-3.0.
Only change: `android_dns.go` (this folder) added to the package `main` — on Android there is no
/etc/resolv.conf, so DNS goes to $OPENFLUX_DNS (default 77.88.8.8:53).

```bash
git clone --depth 1 --branch v0.17.1 https://github.com/p1neappleXpress/OpenFlux.git
cp android_dns.go OpenFlux/
cd OpenFlux
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath -ldflags "-s -w -checklinkname=0" -o libopenflux.so .   # Go 1.27.4
```

GOOS=android (not linux): Go then reads Android system CA certificates (/system/etc/security/cacerts).
-checklinkname=0: needed by the dependency github.com/wlynxg/anet with Go 1.27.

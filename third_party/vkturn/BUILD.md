# libvkturn.so — how it is built

Source: https://github.com/cacggghp/vk-turn-proxy tag v1.8.3 (commit e8a9696), GPL-3.0, package `./client`.
Rebuilt instead of the upstream client-android-arm64 (4 KB LOAD alignment — not allowed on 16 KB page devices);
a static Go build is 64 KB aligned. Only change: `android_dns.go` (this folder) copied into `client/` — DNS
fallback to $VKTURN_DNS (default 77.88.8.8:53) when /etc/resolv.conf is missing (Android).

```bash
git clone --depth 1 --branch v1.8.3 https://github.com/cacggghp/vk-turn-proxy.git
cp android_dns.go vk-turn-proxy/client/
cd vk-turn-proxy
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o libvkturn.so ./client   # Go 1.27.4
```

# libolcrtc.so — how it is built

Source: https://github.com/openlibrecommunity/olcrtc branch master, commit f3ad8fb (WTFPL), package ./cmd/olcrtc.
Only change: `android_dns.go` (this folder) copied into cmd/olcrtc — DNS fallback to $OLCRTC_DNS
(default 77.88.8.8:53) when /etc/resolv.conf is missing (Android).

```bash
git clone https://github.com/openlibrecommunity/olcrtc && cd olcrtc && git checkout f3ad8fb
cp ../android_dns.go cmd/olcrtc/
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath -ldflags "-s -w -checklinkname=0" -o libolcrtc.so ./cmd/olcrtc   # Go 1.27.4
```
Server (old Germany, /opt/olcrtc/olcrtc): same commit, GOOS=linux GOARCH=amd64, without android_dns.go.

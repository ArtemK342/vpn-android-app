package main

// fsociety (2026-11-17): на Android нет /etc/resolv.conf, а статическая linux-сборка Go без него шлёт DNS на
// 127.0.0.1:53 и не находит ни один адрес. Если файла нет — DNS-запросы идут на OLCRTC_DNS (по умолчанию
// Яндекс 77.88.8.8:53, доступен и при белых списках). На сервере (resolv.conf есть) ничего не меняется.

import (
	"context"
	"net"
	"os"
	"time"
)

func init() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	dns := os.Getenv("OLCRTC_DNS")
	if dns == "" {
		dns = "77.88.8.8:53"
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			return d.DialContext(ctx, network, dns)
		},
	}
}

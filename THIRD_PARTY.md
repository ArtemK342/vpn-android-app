# Third-party binaries shipped in the APK

Unmodified official release binaries, started as separate processes (not linked into the app code).
SHA-256 of each file was checked against the upstream release.

| File in APK (jniLibs/arm64-v8a) | Project | Version | License | Source |
|---|---|---|---|---|
| libxray.so | XTLS/Xray-core (Xray-android-arm64-v8a) | v26.3.27 | MPL-2.0 | https://github.com/XTLS/Xray-core/tree/v26.3.27 |
| libopenflux.so | p1neappleXpress/OpenFlux, built from source + DNS patch (third_party/openflux) | v0.17.1 | GPL-3.0 | https://github.com/p1neappleXpress/OpenFlux/tree/v0.17.1 + third_party/openflux |
| libvkturn.so | cacggghp/vk-turn-proxy client, built from source + DNS fallback (third_party/vkturn) | v1.8.3 | GPL-3.0 | https://github.com/cacggghp/vk-turn-proxy/tree/v1.8.3 + third_party/vkturn |

GPL-3.0 / MPL-2.0: the corresponding source code is available at the links above (exact tags).

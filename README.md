# GboardHookah

GboardHookah is a maintained fork of [chenyue404/GboardHook](https://github.com/chenyue404/GboardHook).

## Goal

Use the configured clipboard capacity and retention window across the Gboard clipboard query paths, including newer `ContentProvider` Bundle queries and SQLite-backed queries. The practical target is that Gboard can expose the same retained clipboard history that Android's paste popup can already see, instead of being stuck on a small legacy query limit.

## What this fork changes

- Keeps the upstream Gboard 1.3.0 compatibility workarounds.
- Rewrites legacy `ClipboardContentProvider` limits generically instead of matching only `timestamp DESC limit 5`.
- Supports modern Bundle query limits (`QUERY_ARG_SQL_LIMIT` and `QUERY_ARG_LIMIT`).
- Covers relevant SQLite `query(...)` and clipboard `rawQuery(...)` paths.
- Keeps timestamp retention rewriting tied to the configured retention value.
- Adds an optional **Sync Android clipboard capacity** switch. When enabled, the configured capacity is applied to the extended Gboard-backed paste-history query paths as well as the legacy Gboard path. When disabled, GboardHookah falls back to the conservative upstream-style legacy capacity rewrite.
- Uses defensive hook callbacks so a changed Gboard method does not crash the target process just because one optional hook path no longer matches.
- Shows authenticated live runtime status from the injected Gboard process. Status refresh uses an ordered request/result path and does not depend on Gboard being allowed to resolve the GboardHookah package through app-list visibility filters such as Hide My Applist.
- Runtime-status requests are authenticated by an Android signature-level permission owned by GboardHookah. The injected Gboard receiver therefore no longer depends on LSPosed exposing a newly written shared token or on framework sender-package attribution. The request token is used only as a correlation nonce for the ordered result.
- Synchronizes the current capacity, retention, sync and debug settings over that same authenticated channel into the injected Gboard process. The injected side persists them in Gboard-private storage and the functional clipboard hooks read from that runtime config, avoiding stale/default values from the legacy XSharedPreferences bridge.
- Preserves the active runtime-config source through the app-side status snapshot fallback so a valid `authenticated-request` result is not replaced by a misleading `unknown` label after the follow-up push.
- Provides a **Copy log** button below Runtime status that copies the complete currently displayed status block to the Android clipboard for easy diagnostics sharing.
- Provides a **Restart Gboard** button. With root approval it sends `TERM` only to the current Gboard main process, preserves the configured default IME, does not use `am force-stop`, and automatically refreshes runtime status after the restart.
- Uses its own application id: `com.lycidias93.gboardhookah`.
- Uses an English-only settings UI, CI debug builds for development verification, and a separate stable-signed public release path.

Android's framework clipboard itself exposes one current system clip rather than a configurable multi-item history. The synchronization switch therefore targets the Gboard-backed history/query surfaces used by Gboard and Android paste UI integrations; it does not invent or modify a separate Android OS history database.

## Installation / rollback

Use the APK attached to a GitHub **Release** for normal installation and updates. GitHub Actions CI debug artifacts are test builds, not the stable update channel.

GboardHookah can be installed next to another GboardHook fork because it has a separate package id. Do **not** enable two clipboard hook modules for Gboard at the same time in LSPosed. Disable the old module before enabling GboardHookah.

The first public stable release introduces a persistent release-signing identity. Pre-release CI/test APKs used ephemeral Android debug signing, so a device currently running one of those development builds needs a one-time uninstall before installing the first stable release. Use **Copy log** or otherwise note the current settings before uninstalling, then re-enter the desired GboardHookah settings and verify LSPosed enablement/scope after the migration. Later public releases are intended to keep the same signing identity and support normal in-place upgrades.

Rollback is simply: disable GboardHookah and re-enable the previous module. No clipboard database migration is performed by this module.

Opening GboardHookah or pressing **Refresh status** synchronizes the app's saved configuration into the injected Gboard process. **Apply** persists the edited settings and immediately performs the same authenticated runtime sync. **Copy log** copies the displayed Runtime status text without changing any runtime state. **Restart Gboard** remains available to force a clean process reload; first-time LSPosed/module setup may still require a phone reboot.

If live status remains unavailable while LSPosed shows GboardHookah enabled and scoped to Gboard, collect the GboardHookah LSPosed status log rather than changing app-hiding policy blindly. Older versions used either a reverse explicit broadcast, an LSPosed-shared token, or framework sender attribution for request authentication. Version `1.4.14-hookah.15` moved request authentication to a signature-level Android permission. Version `1.4.15-hookah.16` additionally moves functional configuration delivery off the stale LSPosed shared-preference path and reports the active config source in Runtime status. Version `1.4.16-hookah.17` keeps that config-source field intact across the app-side snapshot/push fallback. Version `1.4.17-hookah.18` adds one-tap copying of the visible Runtime status block to the Android clipboard. Version `1.4.18-hookah.19` prepares the first stable-signed public release channel.

See [CHANGELOG.md](CHANGELOG.md) for the complete end-user fork changelog. Maintainer signing details are documented in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## Credits

Original project and core hooking approach: [chenyue404/GboardHook](https://github.com/chenyue404/GboardHook), GPL-3.0.

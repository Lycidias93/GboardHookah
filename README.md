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
- Provides a **Restart Gboard** button. With root approval it sends `TERM` only to the current Gboard main process, preserves the configured default IME, does not use `am force-stop`, and automatically refreshes runtime status after the restart.
- Uses its own application id: `com.lycidias93.gboardhookah`.
- Uses an English-only settings UI and reproducible debug APK builds in GitHub Actions.

Android's framework clipboard itself exposes one current system clip rather than a configurable multi-item history. The synchronization switch therefore targets the Gboard-backed history/query surfaces used by Gboard and Android paste UI integrations; it does not invent or modify a separate Android OS history database.

## Installation / rollback

GboardHookah can be installed next to another GboardHook fork because it has a separate package id. Do **not** enable two clipboard hook modules for Gboard at the same time in LSPosed. Disable the old module before enabling GboardHookah.

Rollback is simply: disable GboardHookah and re-enable the previous module. No clipboard database migration is performed by this module.

After applying changed settings, use **Restart Gboard** or restart Gboard manually. First-time LSPosed/module setup may still require a phone reboot.

If live status remains unavailable while LSPosed shows GboardHookah enabled and scoped to Gboard, collect the GboardHookah LSPosed status log rather than changing app-hiding policy blindly. Older versions used either a reverse explicit broadcast, an LSPosed-shared token, or framework sender attribution for request authentication. Version `1.4.14-hookah.15` moves request authentication to a signature-level Android permission and keeps the ordered request result as the primary status return path, removing those dependencies.

## Credits

Original project and core hooking approach: [chenyue404/GboardHook](https://github.com/chenyue404/GboardHook), GPL-3.0.

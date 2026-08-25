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
- Shows authenticated live runtime status from the injected Gboard process. Status refresh uses the ordered request result as its primary return path, so it does not depend on Gboard being allowed to resolve the GboardHookah package through app-list visibility filters such as Hide My Applist; the older explicit push channel remains as a compatibility fallback.
- On Android 14 and newer, runtime-status requests additionally validate Android's framework-reported sending package before accepting the session token. This keeps status refresh working when LSPosed's legacy shared-preference bridge does not expose a newly written status token inside Gboard. Older Android versions retain the shared-token validation fallback.
- Provides a **Restart Gboard** button. With root approval it sends `TERM` only to the current Gboard main process, preserves the configured default IME, does not use `am force-stop`, and automatically refreshes runtime status after the restart.
- Uses its own application id: `com.lycidias93.gboardhookah`.
- Uses an English-only settings UI and reproducible debug APK builds in GitHub Actions.

Android's framework clipboard itself exposes one current system clip rather than a configurable multi-item history. The synchronization switch therefore targets the Gboard-backed history/query surfaces used by Gboard and Android paste UI integrations; it does not invent or modify a separate Android OS history database.

## Installation / rollback

GboardHookah can be installed next to another GboardHook fork because it has a separate package id. Do **not** enable two clipboard hook modules for Gboard at the same time in LSPosed. Disable the old module before enabling GboardHookah.

Rollback is simply: disable GboardHookah and re-enable the previous module. No clipboard database migration is performed by this module.

After applying changed settings, use **Restart Gboard** or restart Gboard manually. First-time LSPosed/module setup may still require a phone reboot.

If live status remains unavailable while LSPosed shows GboardHookah enabled and scoped to Gboard, check app-hiding policy. Older versions returned status through a Gboard-to-GboardHookah explicit broadcast, which could be filtered when Gboard was configured to hide `com.lycidias93.gboardhookah`. Version `1.4.12-hookah.13` and later return refresh data through the ordered request channel first, avoiding that reverse package-resolution dependency. Version `1.4.13-hookah.14` additionally removes the Android 14+ status request's dependency on the LSPosed-shared token being visible inside Gboard by authenticating the sending package through framework sender attribution and then binding the response to the request token for the current Gboard process session.

## Credits

Original project and core hooking approach: [chenyue404/GboardHook](https://github.com/chenyue404/GboardHook), GPL-3.0.

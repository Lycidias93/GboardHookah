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
- Uses its own application id: `com.lycidias93.gboardhookah`.
- Uses an English-only settings UI and reproducible debug APK builds in GitHub Actions.

Android's framework clipboard itself exposes one current system clip rather than a configurable multi-item history. The synchronization switch therefore targets the Gboard-backed history/query surfaces used by Gboard and Android paste UI integrations; it does not invent or modify a separate Android OS history database.

## Installation / rollback

GboardHookah can be installed next to another GboardHook fork because it has a separate package id. Do **not** enable two clipboard hook modules for Gboard at the same time in LSPosed. Disable the old module before enabling GboardHookah.

Rollback is simply: disable GboardHookah and re-enable the previous module. No clipboard database migration is performed by this module.

After applying changed settings, restart Gboard. First-time LSPosed/module setup may require a phone reboot.

## Credits

Original project and core hooking approach: [chenyue404/GboardHook](https://github.com/chenyue404/GboardHook), GPL-3.0.

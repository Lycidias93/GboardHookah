# Changelog

This changelog contains user-facing changes only. The first public release lists the complete practical delta of the GboardHookah fork from the upstream `chenyue404/GboardHook` baseline it was forked from.

## [1.4.18-hookah.19] - 2026-08-25

Initial public release of the GboardHookah fork.

### Clipboard history and capacity

- Extends clipboard-capacity handling beyond the narrow upstream legacy query pattern so newer Gboard history paths are covered as well.
- Rewrites legacy `ClipboardContentProvider` limits generically instead of depending on one exact `timestamp DESC limit 5` form.
- Supports modern provider limit arguments when the installed Gboard exposes them, and also covers relevant `SQLiteDatabase.query(...)` and clipboard `rawQuery(...)` paths.
- Keeps the Gboard HashSet compatibility path used by newer Gboard builds and includes it in live capacity proof.
- Adds **Follow Android clipboard history capacity**, enabled by default. In this mode GboardHookah removes its own capacity cap from the observed Gboard history path so Gboard can follow the retained history available underneath it.
- Keeps a separate **Manual Gboard clipboard capacity** for users who turn history-capacity sync off, and preserves that manual value while sync is enabled.
- Disables and visually dims the manual-capacity field while automatic history-capacity sync is active so the effective behavior is clear.
- Applies the configured clipboard-retention window across the supported Gboard query paths instead of leaving the runtime on the upstream/default retention value.
- Treats an already-unbounded Gboard history query as a successful uncapped path rather than falsely reporting that no capacity hook worked.

### Runtime configuration and live proof

- Adds a **Runtime status** screen that reports a live response from the actually injected Gboard process instead of inferring health from LSPosed configuration alone.
- Shows the running Gboard version and process, capacity-sync state, configured and effective capacity, retention, debug state, active hook watchers, observed query paths, capacity-hook proof and runtime errors.
- Uses an authenticated status channel so a stale or missing shared-preference token cannot be mistaken for a working hook.
- Makes status refresh resilient to app-list hiding such as Hide My Applist by avoiding a dependency on Gboard resolving the GboardHookah package for the primary response path.
- Protects the Gboard-side status/config receiver with an Android signature-level permission. The request token is used as a per-request correlation nonce rather than as the sole trust mechanism.
- Synchronizes the app's saved capacity, retention, sync and debug configuration through the authenticated runtime channel into Gboard-private storage, avoiding stale/default values from LSPosed's legacy cross-package SharedPreferences bridge on newer Android builds.
- Keeps the active runtime-config source visible and preserves it across the status snapshot fallback, so a valid `authenticated-request` result is not replaced by a misleading `unknown` label.
- Captures real Gboard application/provider contexts for runtime status and hook telemetry, avoiding false offline states caused by lifecycle callbacks that current Gboard builds do not reliably expose to the older bootstrap approach.
- Reports capacity PASS only after a real supported clipboard query path has been observed; unsupported optional overloads are shown as unsupported instead of being treated as failures.

### Settings and usability

- Uses the dedicated package id `com.lycidias93.gboardhookah`, allowing the fork to be installed alongside another GboardHook package for controlled migration and rollback.
- Uses an English-only settings UI regardless of device locale.
- **Apply** now keeps GboardHookah open, shows a confirmation message and synchronizes the saved configuration immediately instead of redirecting to Gboard's App Info page.
- Adds persistent labels for the numeric capacity and retention fields so their meaning remains visible while values are entered.
- Adds **Restart Gboard**. With root approval it sends `TERM` only to the current Gboard main process, preserves the configured default keyboard and avoids `am force-stop`.
- Adds **Copy log**, which copies the complete currently displayed Runtime status block to the Android clipboard for support and diagnostics sharing.
- Improves no-response guidance so LSPosed scope and app-hiding rules can be checked without assuming either one is the cause.

### Compatibility and reliability

- Fixes the Xposed API call signature used by the module so hooks register correctly against the real LSPosed runtime instead of failing with `NoSuchMethodError`.
- Uses defensive hook callbacks so a changed or missing optional Gboard method does not crash the target process merely because one compatibility path is unavailable.
- Supports Android devices that require 16 KB native-library ZIP alignment, including the bundled DexKit native library.
- Public builds are non-debuggable and keep the live-verified non-minified Xposed entry-point behavior for the initial stable release.

### Installation and migration

- **Do not enable two Gboard clipboard-hook modules at the same time in LSPosed.** Disable the previous clipboard-hook module before enabling GboardHookah for Gboard.
- Public GitHub releases now use one stable release signing identity. The earlier pre-release GitHub Actions/test APKs were signed with ephemeral Android debug keys and are not a stable update channel.
- If a pre-release CI/test APK is currently installed, the first stable release requires a one-time uninstall and reinstall because Android will not treat the new stable signing identity as an in-place update of those ephemeral debug signatures. Re-enter GboardHookah settings and verify the LSPosed module/scope after that one-time migration.
- After the first stable release is installed, later public releases are intended to use the same signing identity and support normal in-place upgrades.
- GboardHookah does not migrate or rewrite the clipboard database during install, update or rollback.

### Known limitations

- Hook availability depends on the installed Gboard build. On the currently verified Gboard 18.0.3 beta build, the legacy provider path is active and proves uncapped history successfully while one modern Bundle-provider overload is not exposed by that build.
- **Restart Gboard** requires root access. The clipboard hooks themselves still require the normal LSPosed/root environment expected by this project.
- First-time LSPosed activation or scope changes can still require a device reboot depending on the device/runtime state.

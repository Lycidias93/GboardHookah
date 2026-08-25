# Release signing

Public GboardHookah APKs must use one long-lived Android signing key. Do not publish GitHub Actions debug artifacts as stable releases: GitHub-hosted runners create a fresh Android debug keystore when no fixed key is supplied, so those APKs can have different signing certificates from run to run.

## Required GitHub Actions secrets

Configure these repository Actions secrets before running **Publish signed GboardHookah release**:

- `GBOARDHOOKAH_RELEASE_KEYSTORE_B64` — base64 of the release keystore file.
- `GBOARDHOOKAH_RELEASE_STORE_PASSWORD` — keystore password.
- `GBOARDHOOKAH_RELEASE_KEY_ALIAS` — signing-key alias.
- `GBOARDHOOKAH_RELEASE_KEY_PASSWORD` — key password when it differs from the store password; it may be left unset when both passwords are the same.
- `GBOARDHOOKAH_RELEASE_CERT_SHA256` — SHA-256 fingerprint of the signing certificate. This is a trust anchor used by the workflow to reject an accidental signing-key change.

The private keystore and passwords must never be committed to the repository. Keep at least one secure offline backup of the keystore and its credentials; losing the private key prevents normal upgrades of already-installed public releases.

The repository ignores `app/AndroidKeystore/` so local Android Studio/Gradle signing material is not accidentally committed.

## Local signing compatibility

`app/build.gradle` still supports the existing local `app/AndroidKeystore/keystore.properties` flow. The public-release workflow instead injects the signing values through environment variables and materializes the keystore only for the lifetime of the GitHub-hosted runner.

The first public release intentionally keeps release minification disabled. Xposed loads multiple entry classes by their literal names from `assets/xposed_init`, and the live-accepted development builds are non-minified. A future minified build should only be enabled after all Xposed entry points and reflective/hooked classes have explicit keep coverage and runtime acceptance.

## Publication flow

1. Keep the release version in `app/build.gradle` and the matching top section in `CHANGELOG.md` synchronized.
2. Merge the release candidate to `master` only after the normal build workflow is green.
3. In GitHub Actions, run **Publish signed GboardHookah release** from `master`.
4. The workflow decodes the private keystore, builds `assembleRelease`, verifies 16 KB ZIP alignment, verifies the APK signature, compares the actual certificate fingerprint with `GBOARDHOOKAH_RELEASE_CERT_SHA256`, extracts the matching user-facing changelog section, and creates the GitHub Release/tag with the signed APK attached.
5. Never rotate the public signing key casually. A key change is an Android package-signature migration and must be treated as a breaking install/update event.

## First stable-release migration

All pre-public-release CI APKs were development artifacts built with Android debug signing. Because GitHub-hosted runners are ephemeral, those debug signing identities were not stable across builds.

For a device that already has one of those test APKs installed, the first public stable APK therefore requires a one-time uninstall/reinstall. App preferences are normally removed by uninstall, so re-enter the desired GboardHookah settings and verify LSPosed enablement/scope afterward. Once the first stable APK is installed, subsequent stable releases must keep the same release signing key so normal in-place upgrades work.

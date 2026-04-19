# Releasing the Waddle Android APK

The release workflow (`.github/workflows/release-apk.yml`) builds a signed APK on every push to `main` and every pull request. For the APK to be **update-installable on top of a previous build**, every CI run must sign with the *same* keystore. This document describes the one-time setup.

## One-time: generate a release keystore

On any machine with the JDK installed, run once:

```bash
keytool -genkeypair \
  -keystore waddle-release.keystore \
  -storetype PKCS12 \
  -storepass 'CHOOSE_A_STRONG_PASSWORD' \
  -keypass   'CHOOSE_A_STRONG_PASSWORD' \
  -alias     'waddle-release' \
  -keyalg    RSA \
  -keysize   4096 \
  -validity  10950 \
  -dname 'CN=Waddle, O=Waddle Social, C=US'
```

Keep `waddle-release.keystore` **and the passwords** in a safe place — if you lose them you cannot ship an update to anyone who already has the app installed.

## One-time: publish the keystore to GitHub Actions

Base64-encode the keystore and add four secrets to the repository:

```bash
base64 -i waddle-release.keystore | pbcopy  # macOS
# or
base64 -w0 waddle-release.keystore           # Linux
```

In the repo settings → **Secrets and variables → Actions**, add:

| Secret                      | Value                                               |
|-----------------------------|-----------------------------------------------------|
| `RELEASE_KEYSTORE_BASE64`   | the base64 output from above                        |
| `RELEASE_KEYSTORE_PASSWORD` | the `-storepass` value                              |
| `RELEASE_KEY_ALIAS`         | `waddle-release`                                    |
| `RELEASE_KEY_PASSWORD`      | the `-keypass` value                                |

From the next CI run onwards, the APK is signed with this stable key and can be installed over any previous release.

## Version codes

`versionCode` is generated from UTC seconds since 2020-01-01 unless `VERSION_CODE` is set explicitly. CI uses the same timestamp scheme and sets `versionName` to `0.1.0+<run-number>-<short-sha>`. Local builds without an explicit code use `0.1.0+local.<versionCode>`, which makes the APK identifier visible when inspecting an installed build.

Android accepts an APK update only when the application ID is unchanged, the signing certificate matches, and the new `versionCode` is not lower than the installed one. Keep debug and release APKs separate: debug builds install as `social.waddle.android.debug`, release builds install as `social.waddle.android`.

## Without the secrets

If the secrets are missing, the workflow falls back to a throwaway keystore regenerated every run and emits a warning in the job summary. The resulting APK is installable on a clean device but **cannot update-install over any previous build** — users will see `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and need to uninstall first.

Set up the secrets above to avoid that.

# Publishing SpeakerLink

SpeakerLink has two apps in this repository, released separately:

| App | Tag | Published to |
| --- | --- | --- |
| Android | `android-v1.2.3` | GitHub Releases (signed APK) and F-Droid (built from source, reproducible) |
| Mac | `mac-v1.2.3` | GitHub Releases (zipped app; Developer ID signed and notarized when the secrets exist) |

The repository must stay public: F-Droid only builds from public repositories, and release
downloads of a private repository need a login.

# Android

With reproducible builds F-Droid ships the same signed APK as GitHub, so users can switch between
the two without reinstalling.

## 0. Before the first release
* **The application ID is permanent:** `io.github.cyrus_sch.speakerlink`, which follows the
  `cyrus-sch.github.io` convention for the GitHub user `Cyrus-Sch`.
* **F-Droid's inclusion policy** asks for about three months of development history. The project
  started on 2026-10-16, so either submit from mid-January 2027, or explain in the merge request
  why it should be accepted sooner.

## 1. Create the signing key (once)

Keep the keystore and passwords safe. Losing them means existing users can no longer update.

```sh
keytool -genkeypair -v -keystore speakerlink-release.jks -alias speakerlink \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=SpeakerLink, O=Cyrus-Sch"
base64 -w0 speakerlink-release.jks > speakerlink-release.jks.b64
```

Add four repository secrets (GitHub, Settings > Secrets and variables > Actions):

| Secret | Value |
| --- | --- |
| `SPEAKERLINK_KEYSTORE_BASE64` | contents of `speakerlink-release.jks.b64` |
| `SPEAKERLINK_KEYSTORE_PASSWORD` | the keystore password |
| `SPEAKERLINK_KEY_ALIAS` | `speakerlink` |
| `SPEAKERLINK_KEY_PASSWORD` | the key password (for a PKCS12 keystore, which `keytool` creates by default, the same as the keystore password) |

Note the certificate fingerprint for F-Droid (lowercase, no colons):

```sh
keytool -list -v -keystore speakerlink-release.jks -alias speakerlink \
  | grep 'SHA256:' | sed 's/.*SHA256: //; s/://g' | tr 'A-F' 'a-f'
```

## 2. Release on GitHub

1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`. Keep them plain literals:
   F-Droid reads them from the file.
2. Add `android/app/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max).
3. Commit to the default branch, then tag and push:

   ```sh
   git tag android-v1.0.0
   git push origin android-v1.0.0
   ```

CI builds and tests the tagged commit, builds the release APK with JDK 21, signs it, verifies the
signature and creates the GitHub release `android-v1.0.0` with `SpeakerLink-1.0.0.apk`.

## 3. Submit to F-Droid (first release only)

1. Fork <https://gitlab.com/fdroid/fdroiddata> on GitLab and create a branch named
   `io.github.cyrus_sch.speakerlink`.
2. Copy `android/fdroid/io.github.cyrus_sch.speakerlink.yml` to `metadata/` in your fork. Fill in the
   full commit hash of the tag (`git rev-parse android-v1.0.0^{commit}`) and the certificate
   fingerprint from step 1.
3. Check it locally if you can (fdroidserver):
   `fdroid readmeta`, `fdroid rewritemeta io.github.cyrus_sch.speakerlink` (must give no diff),
   `fdroid lint io.github.cyrus_sch.speakerlink`, `fdroid build -v -l io.github.cyrus_sch.speakerlink`.
4. Push and open a merge request titled "New app: SpeakerLink" using the *App inclusion*
   template. In the description, explain:
   * the Bluetooth permissions (`neverForLocation`; location only up to Android 11, for discovery),
   * the reflection on hidden Bluetooth APIs (`BluetoothA2dp.connect` and related; on the
     non-SDK "unsupported" list, with fallbacks),
   * that the app has no network access,
   * that the build is reproducible (CI compares two clean builds byte for byte).
5. Answer reviewer questions quickly. Review usually takes one to six weeks, and publishing takes
   a day or two after the merge.

Later releases need no merge request. F-Droid's update checker finds new `android-v*` tags,
reads the version from `android/app/build.gradle.kts` and builds them automatically.

## Store listing

F-Droid reads the listing from `android/app/fastlane/metadata/android/en-US/`: `title.txt`,
`short_description.txt`, `full_description.txt`, `changelogs/`, and `images/` (icon, feature
graphic, phone screenshots). CI's emulator job renders the screenshots (`ScreenshotTest`). To
refresh them, run the Android workflow by hand (Actions > Android > Run workflow) with
"update_screenshots" ticked: it commits the new images to the listing.

## Google Play (optional)

The app is Play-compatible (targetSdk 36), but Play needs a developer account, a privacy policy
URL, the Data safety form, and a closed test with 12 testers for 14 days on new personal accounts.
Google's developer verification, rolling out to all certified devices in 2027, may also affect
sideloaded and F-Droid installs.


# Mac

## Version and changelog

* `mac/VERSION`: the version people see (for example `1.0.0`).
* `mac/BUILD`: a whole number that must grow with every release.
* `mac/CHANGELOG.md`: a section per version; the release notes come from it.

## Release

Commit to the default branch, then tag and push:

```sh
git tag mac-v1.0.0
git push origin mac-v1.0.0
```

CI checks that the tag matches `mac/VERSION`, runs the tests, builds a universal (Apple silicon
and Intel) app, signs it, zips it and creates the GitHub release "SpeakerLink for Mac 1.0.0"
with `SpeakerLink-1.0.0.zip` and its SHA-256.

## Signing

**Without an Apple Developer account** (the default), the app is ad-hoc signed. It works, but
macOS 15 and later block it on first launch until the user clicks **Open Anyway** in System
Settings > Privacy & Security ([mac/README.md](mac/README.md) explains this). The signature keeps a
stable identity, so the Bluetooth permission survives updates.

**With a Developer ID** (Apple Developer Program, 99 USD a year), CI signs with the hardened
runtime and notarizes the app, so it opens without warnings. Add six repository secrets:

| Secret | Value |
| --- | --- |
| `MAC_DEVELOPER_ID_P12_BASE64` | your "Developer ID Application" certificate and key, exported from Keychain Access as .p12, then `base64 -i cert.p12` |
| `MAC_DEVELOPER_ID_P12_PASSWORD` | the password you gave the .p12 |
| `MAC_SIGN_IDENTITY` | the identity name, e.g. `Developer ID Application: Your Name (TEAMID1234)` |
| `MAC_NOTARY_KEY_P8_BASE64` | an App Store Connect API key (Users and Access > Integrations > Team Keys, "Developer" role): `base64 -i AuthKey_XXXX.p8` |
| `MAC_NOTARY_KEY_ID` | that key's ID |
| `MAC_NOTARY_ISSUER_ID` | the issuer ID shown above the key list |

When all six are set, the release job signs and notarizes; otherwise it builds ad-hoc and says
so in the release notes.

## Homebrew (optional)

The official Homebrew cask repository accepts only signed and notarized apps from projects that
are at least 30 days old and reasonably popular. Until then, a personal tap
(`Cyrus-Sch/homebrew-tap` with a `speakerlink` cask pointing at the release zip) can offer
`brew install --cask cyrus-sch/tap/speakerlink`.

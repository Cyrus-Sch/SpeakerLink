# Publishing SpeakerLink

SpeakerLink is published in two places:

1. **GitHub Releases**: a signed APK, built by CI when a `speakerlink-v*` tag is pushed.
2. **F-Droid**: F-Droid builds the app from source. With reproducible builds it ships the same
   signed APK as GitHub, so users can switch between the two without reinstalling.

## 0. Before the first release

* **The source must be public.** F-Droid only builds from public repositories, and release
  downloads of a private repository need a login. Either move `SpeakerLink/` into its own public
  repository (recommended; it also keeps the unrelated C# project and its 14 MB of DLLs out of
  F-Droid's source tarball), or make this repository public.
  If you move it, update `SOURCE_URL` in `app/src/main/java/.../ui/InfoScreen.kt`, the URLs in
  `fdroid/io.github.cyrus_sch.speakerlink.yml`, and move the workflow to the new repository's
  `.github/workflows/` (with `working-directory: .` instead of `SpeakerLink`).
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
| `SPEAKERLINK_KEY_PASSWORD` | the key password |

Note the certificate fingerprint for F-Droid (lowercase, no colons):

```sh
keytool -list -v -keystore speakerlink-release.jks -alias speakerlink \
  | grep 'SHA256:' | sed 's/.*SHA256: //; s/://g' | tr 'A-F' 'a-f'
```

## 2. Release on GitHub

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. Keep them plain literals:
   F-Droid reads them from the file.
2. Add `app/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max).
3. Commit to the default branch, then tag and push:

   ```sh
   git tag speakerlink-v1.0.0
   git push origin speakerlink-v1.0.0
   ```

CI builds and tests the tagged commit, builds the release APK with JDK 21, signs it, verifies the
signature and creates the GitHub release `speakerlink-v1.0.0` with `SpeakerLink-1.0.0.apk`.

## 3. Submit to F-Droid (first release only)

1. Fork <https://gitlab.com/fdroid/fdroiddata> on GitLab and create a branch named
   `io.github.cyrus_sch.speakerlink`.
2. Copy `fdroid/io.github.cyrus_sch.speakerlink.yml` to `metadata/` in your fork. Fill in the
   full commit hash of the tag (`git rev-parse speakerlink-v1.0.0^{commit}`) and the certificate
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

Later releases need no merge request. F-Droid's update checker finds new `speakerlink-v*` tags,
reads the version from `app/build.gradle.kts` and builds them automatically.

## Store listing

F-Droid reads the listing from `app/fastlane/metadata/android/en-US/`: `title.txt`,
`short_description.txt`, `full_description.txt`, `changelogs/`, and `images/` (icon, feature
graphic, phone screenshots). CI's emulator job renders the screenshots
(`ScreenshotTest`, artifact `speakerlink-emulator`); copy them to `images/phoneScreenshots/`.

## Google Play (optional)

The app is Play-compatible (targetSdk 36), but Play needs a developer account, a privacy policy
URL, the Data safety form, and a closed test with 12 testers for 14 days on new personal accounts.
Google's developer verification, rolling out to all certified devices in 2027, may also affect
sideloaded and F-Droid installs.

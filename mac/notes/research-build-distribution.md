# SUMMARY
Build and distribution research for SpeakerLink for Mac (SwiftPM only, built on GitHub Actions), checked against primary sources on 2026-10-17.

NOTE ON SCOPE: the relayed user request says "move it to its own repo". Everything below assumes a standalone repo with Package.swift at the root and its own .github/workflows.

TOOLCHAINS AND RUNNERS (actions/runner-images main, Oct 2026)
- `macos-latest` = `macos-26` = macOS 26 arm64. Image 20260907.0351.1, macOS 26.6.2 (25G83). Default Xcode 26.6 (17F113), which ships Swift 6.3 (6.3.3 per one PR). Also installed: 26.5, 26.4.1, 26.3, 26.2, 26.1.1, 26.0.1, as `/Applications/Xcode_26.x.app`. SDKs macosx26.0 to 26.5.
- `macos-26-intel` / `macos-26-large` / `macos-latest-large`: x64.
- `macos-15`: arm64, default Xcode 16.4 (Swift 6.1.x), plus 26.0.1 to 26.3. `macos-15-intel`: x64.
- `macos-14`: deprecated, fully unsupported 2026-11-02 (issue #13518).
- `xcode-27` / `xcode-27-xlarge`: PREVIEW (issue #14404). Base OS is now macOS 27.0.1 (26A434). Default Xcode 27.0 (27A266a, Swift 6.4); also 27.1 RC and 27.2 beta 2. No `macos-27` label exists yet.
- Select Xcode with `sudo xcode-select -s /Applications/Xcode_26.6.app` or `DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer`.
- Current action versions: actions/checkout v7.0.1, actions/upload-artifact v7.0.2.
- Xcode 27 facts: Swift 6.4; runs only on Apple-silicon Macs with macOS 26.6+; the ld64 linker and `-ld_classic` were removed. x86_64 is dropped from ARCHS_STANDARD only when the deployment target is 27 or later, and the macOS 27 SDK still back-deploys Universal apps to macOS 12+.
- macOS 27 is Apple-silicon only, and Rosetta stays general-purpose through macOS 27.

SWIFTPM BUILD SYSTEM (important)
- SwiftPM 6.4 (Swift 6.4, Sept 15 2026; 6.4.1 current) makes Swift Build the default. Artifacts go to a different place, so always resolve paths with `swift build ... --show-bin-path`. The fallback is `--build-system native`.
- Universal syntax: `swift build -c release --arch arm64 --arch x86_64`.
- Under Swift 6.3 (Xcode 26.x), a multi-arch build goes through xcbuild. Output is `.build/apple/Products/Release/<exe>` (confirmed by pointfreeco/pfw on macos-26 with Xcode 26.2). It needs full Xcode, not just the Command Line Tools.
- KNOWN BUG with Swift 6.4 / swiftbuild: LC_BUILD_VERSION gets `sdk` = the deployment target (for example minos 14.0, sdk 14.0). AppKit then runs the app in the legacy (pre-Liquid Glass) look on macOS 26+.
  - Workaround: `-Xlinker -platform_version -Xlinker macos -Xlinker 14.0 -Xlinker $(xcrun --sdk macosx --show-sdk-version)`, or rewrite the field with vtool.
  - In CI, check it with `vtool -show-build` or `otool -l | grep -A4 LC_BUILD_VERSION`.
  - Building on macos-26 with Xcode 26.6 (Swift 6.3, native build system by default) is not affected according to the reports.

BUNDLE LAYOUT
- `SpeakerLink.app/Contents/{Info.plist, MacOS/SpeakerLink, Resources/AppIcon.icns}`. Optionally add `PkgInfo`.
- Avoid SwiftPM `resources:` in the app target. The generated `Bundle.module` looks in the .app root, but a `.bundle` at the root breaks codesign ("unsealed contents present in the bundle root"). The bundle layout also differs between llbuild and xcbuild. Copy assets into Contents/Resources yourself and load them with `Bundle.main`.
- Exclude a source-tree Info.plist with `exclude:` so SwiftPM does not warn about unhandled files.

INFO.PLIST
- Required keys: CFBundleExecutable, CFBundleIdentifier, CFBundleName, CFBundleDisplayName, CFBundlePackageType=APPL, CFBundleShortVersionString, CFBundleVersion, CFBundleInfoDictionaryVersion=6.0, CFBundleIconFile=AppIcon, LSMinimumSystemVersion=14.0, LSUIElement=true, LSApplicationCategoryType=public.app-category.utilities, NSBluetoothAlwaysUsageDescription.
- CFBundleIdentifier may contain only A-Z, a-z, 0-9, hyphen and period, and is case-insensitive. Underscores are not allowed. Example: `io.github.cyrus-sch.speakerlink`.
- CFBundleVersion is 1 to 3 period-separated integers.

APP ICON
- Build an iconset from a 1024 PNG with sips: `icon_{16,32,128,256,512}x{same}.png` plus `@2x` of each, then `iconutil -c icns AppIcon.iconset -o AppIcon.icns`.
- macOS grid: about 824x824 artwork centred on a 1024 canvas.

SIGNING
- Apple silicon requires every executable to be signed, and an ad-hoc signature is enough. The linker ad-hoc signs automatically. Ad-hoc signatures can never pass Gatekeeper.
- Re-sign after strip, install_name_tool, or anything that changes the bundle: `codesign --force --sign - <App.app>`. Sign inside-out and avoid `--deep`.
- Ad-hoc signatures give a cdhash-based designated requirement, so TCC (the Bluetooth prompt) forgets the grant on every update. ZoomIt-Mac uses `--requirements '=designated => identifier "<bundle id>"'` to keep grants stable.
- Developer ID build: `codesign --force --timestamp --options runtime --sign "Developer ID Application: NAME (TEAMID)" App.app`.

NOTARIZATION
- Zip with `ditto -c -k --keepParent App.app App.zip`.
- Submit with `xcrun notarytool submit App.zip --key AuthKey.p8 --key-id $ASC_KEY_ID --issuer $ASC_ISSUER_ID --wait --timeout 20m --output-format json`. Check that status == Accepted, otherwise fetch `notarytool log <id>`.
- Then `xcrun stapler staple App.app` and re-zip. You cannot staple to a zip.
- Verify with `spctl --assess --type execute --verbose=2` and `stapler validate`.
- Apple's limit is 75 notarizations per day.
- Temporary keychain in CI: `security create-keychain` / `set-keychain-settings -lut 21600` / `unlock-keychain` / `import -P ... -T /usr/bin/codesign` / `set-key-partition-list -S apple-tool:,apple: -s -k` / `list-keychains -d user -s <kc> <existing>`.
- Secrets: DEVELOPER_ID_P12_BASE64, DEVELOPER_ID_P12_PASSWORD, ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8. KEYCHAIN_PASSWORD is optional because it can be generated with uuidgen.

GATEKEEPER
- Since macOS 15 Sequoia, Control-click > Open no longer overrides Gatekeeper.
- The user must go to System Settings > Privacy & Security, scroll to Security, click "Open Anyway" for the blocked app, confirm with Open, and authenticate as an admin. The app is then stored as an exception.
- Gatekeeper blocks only quarantined apps (browser download, AirDrop, Homebrew cask). The no-admin alternative is `xattr -dr com.apple.quarantine /Applications/SpeakerLink.app`.
- No macOS 26 or 27 change to this flow was found.

HOMEBREW
- Homebrew 5.0.0 (2025-11-12) deprecated casks that fail Gatekeeper and deprecated `--no-quarantine`. Those casks were disabled in homebrew/cask on 2026-09-01. Official casks must pass Gatekeeper, so they need Developer ID plus notarization.
- Notability rules for an official cask: 30 forks / 30 watchers / 75 stars, or 90 / 90 / 225 for a self-submission, and the repo must be at least 30 days old.
- Homebrew 6.0.0 (2026-06-11) requires explicit trust for third-party taps. `brew install --cask user/tap/cask` trusts just that item.
- Homebrew 7.0.x (7.0.0 on 2026-09-13; 7.0.9 current) deprecates Ruby `postflight` blocks in favour of `postflight_steps` (disablement 2027-12-11).
- Personal taps: maintainers say third-party taps are not signing-checked and can host unsigned apps, but Homebrew still quarantines downloads. Some taps strip quarantine with `postflight_steps { run "/usr/bin/xattr", args: [...] }`.

MENUBAREXTRA AND LOGIN ITEM
- MenuBarExtra `.window` style has no first-party API to open/close it programmatically or to reach its NSStatusItem/NSWindow (still true as of Xcode 26; use orchetect/MenuBarExtraAccess).
- A menu-bar-only app is terminated automatically if the user removes the extra.
- Tahoe has System Settings > Menu Bar > "Allow in the Menu Bar" toggles that can hide the item.
- Opening Settings from an LSUIElement app needs the `openSettings` environment action (macOS 14+) plus app activation.
- `SMAppService.mainApp.register()` needs macOS 13. Status values: `.enabled`, `.requiresApproval`, `.notRegistered`, `.notFound`. Use `openSystemSettingsLoginItems()` to send the user to settings.
- Apple DTS says ad-hoc signing causes background-item and login-item problems and recommends an Apple-issued identity.

SWIFT 6 CONCURRENCY
- `AudioObjectPropertyListenerBlock` is a plain non-Sendable `(UInt32, UnsafePointer<AudioObjectPropertyAddress>) -> Void`. A block created in @MainActor code inherits main-actor isolation, and SE-0423 runtime checks crash (`_dispatch_assert_queue_fail`) if the block runs on another queue.
- Pass `DispatchQueue.main` as inDispatchQueue (listeners are then dispatched async, except IO-context properties), or create the block nonisolated/@Sendable and hop to the main actor. A NULL queue means the block is invoked directly on the HAL thread.
- Keep the block so you can pass the same one to `AudioObjectRemovePropertyListenerBlock`.
- IOBluetooth is not thread-safe (Apple staff: "Everything needs to be handled in the main thread").
  - Its Swift declarations have no isolation or Sendable annotations.
  - Use an `@MainActor final class: NSObject` with `@preconcurrency IOBluetoothDeviceAsyncCallbacks`, which requires all three methods.
  - Async `openConnection(self)` delivers `connectionComplete(_:status:)` on the main run loop.
  - Never call the synchronous `openConnection()` / `closeConnection()` on main, because they block.
- Warning control:
  - `swift build -Xswiftc -warnings-as-errors` in CI, or `.treatAllWarnings(as: .error)` (PackageDescription 6.2) in the manifest.
  - `.defaultIsolation(MainActor.self)` (SE-0466, 6.2) is available.
  - `@diagnose` (SE-0522) needs Swift 6.4.
  - An unneeded `@preconcurrency import` itself produces a warning.

# FACTS
- [verified] runner-images README (Oct 2026): macOS 26 arm64 labels are `macos-latest`, `macos-26`, `macos-26-xlarge`. macOS 26 x64 labels are `macos-latest-large`, `macos-26-intel`, `macos-26-large`. macOS 15 arm64 labels are `macos-15`, `macos-15-xlarge`. macOS 15 x64 labels are `macos-15-large`, `macos-15-intel`. macOS 14 (`macos-14`, `macos-14-large`, `macos-14-xlarge`) is deprecated. Xcode 27 is a preview with labels `xcode-27` and `xcode-27-xlarge`.  (https://github.com/actions/runner-images (README.md on main))
- [verified] macos-latest moved to the macOS 26 image starting June 15, 2026 (a 30-day rollout). macOS 14 images began deprecation July 6 and are fully unsupported on 2026-11-02.  (https://github.blog/changelog/2026-05-14-github-actions-upcoming-image-migrations; https://github.com/actions/runner-images/issues/13518)
- [verified] The macos-26 arm64 image (20260907.0351.1, macOS 26.6.2 25G83) defaults to Xcode 26.6 build 17F113 at /Applications/Xcode_26.6.app (symlinked as /Applications/Xcode.app). Also installed: 26.5 (17F42), 26.4.1 (17E202), 26.3 (17C529), 26.2 (17C52), 26.1.1 (17B100), 26.0.1 (17A400). SDKs macosx26.0 to macosx26.5. Command Line Tools 26.6.0.0.1781586589.  (https://raw.githubusercontent.com/actions/runner-images/main/images/macos/macos-26-arm64-Readme.md)
- [verified] The macos-15 arm64 image (20260907.0337.1, macOS 15.7.9 24G830) defaults to Xcode 16.4 (16F6). Xcode 26.0.1 through 26.3 and 16.0 through 16.3 are also installed.  (https://raw.githubusercontent.com/actions/runner-images/main/images/macos/macos-15-arm64-Readme.md)
- [verified] The xcode-27 preview image (20261012.0260.1) runs macOS 27.0.1 (26A434). Default is Xcode 27.0 (27A266a) at /Applications/Xcode_27.app. Also installed: 27.1 RC (27A9275) and 27.2 beta (27B5028f). SDKs macosx27.0 and macosx27.2. There is no `macos-27` label.  (https://raw.githubusercontent.com/actions/runner-images/main/images/macos/xcode-27-arm64-Readme.md; https://github.com/actions/runner-images/issues/14404)
- [verified] Swift versions bundled with Xcode: Xcode 26 ships Swift 6.2, 26.2 ships 6.2.3, 26.4/26.5/26.6 ship 6.3, and 27/27.1 ship 6.4. Xcode 27 needs macOS Tahoe 26.6+, and Xcode 26.6 needs 26.2+.  (https://developer.apple.com/tutorials/data/documentation/xcode-release-notes/xcode-26_6-release-notes.json; https://developer.apple.com/tutorials/data/documentation/xcode-release-notes/xcode-27-release-notes.json; https://developer.apple.com/tutorials/data/documentation/xcode-release-notes/xcode-26_2-release-notes.json)
- [likely] Xcode 26.6 ships Swift 6.3.3 specifically (Apple's notes say only "Swift 6.3"). Xcode 16.4 ships Swift 6.1.x.  (https://github.com/lingyired/status-trio/pull/47)
- [verified] Xcode 27 release notes say: "Xcode 27 will only install and run on Apple silicon Macs. The macOS 27 SDK supports back deploying Universal (Intel and Apple Silicon) apps to macOS 12 and later." ARCHS_STANDARD drops x86_64 only when MACOSX_DEPLOYMENT_TARGET >= 27.0. The ld64 linker was removed and `-ld_classic` is no longer supported.  (https://developer.apple.com/tutorials/data/documentation/xcode-release-notes/xcode-27-release-notes.json)
- [verified] Swift 6.4 was released 2026-09-15 and 6.4.1 is the current install. In SwiftPM 6.4, Swift Build is the default build system. It puts artifacts in a different location, so use `swift build --show-bin-path <args>`. It supports universal builds with `swift build --arch arm64 --arch x86_64`. The fallback is `--build-system native`.  (https://www.swift.org/blog/swift-6.4-released/; https://raw.githubusercontent.com/swiftlang/swift-package-manager/main/Sources/PackageManagerDocs/Documentation.docc/ReleaseNotes/6.4.md)
- [verified] With Swift 6.3 (Xcode 26.x), `swift build -c release --arch arm64 --arch x86_64` uses xcbuild and outputs to `.build/apple/Products/Release/<product>`. It needs a full Xcode install (xcodebuild), not just the Command Line Tools. The resource-bundle layout differs between llbuild (flat) and xcbuild (Contents/Resources).  (https://raw.githubusercontent.com/pointfreeco/pfw/main/.github/workflows/release.yml; https://raw.githubusercontent.com/microsoft/Zoomit-Mac/main/Scripts/build-app.sh)
- [likely] Swift 6.4's default swiftbuild backend writes the deployment target into the LC_BUILD_VERSION `sdk` field (for example minos 14.0, sdk 14.0, even when linking the 27.0 SDK). AppKit then shows the legacy pre-Liquid Glass UI on macOS 26+. Workaround: `-Xlinker -platform_version -Xlinker macos -Xlinker <min> -Xlinker $(xcrun --sdk macosx --show-sdk-version)`, or vtool. Verify with otool or vtool. Builds made with Xcode 26.5/26.6 on the native build system are not affected.  (https://github.com/joaodavidsilva/brightboi/issues/56; https://github.com/lingyired/status-trio/pull/47; https://raw.githubusercontent.com/steipete/CodexBar/main/Scripts/package_app.sh)
- [verified] Apple: CFBundleIdentifier "must contain only alphanumeric characters (A–Z, a–z, and 0–9), hyphens (-), and periods (.)" and bundle IDs are case-insensitive. So underscores are invalid and hyphens are allowed. CFBundleVersion is one to three period-separated integers.  (https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleidentifier; https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleversion)
- [verified] The MenuBarExtra docs say: "An app that only shows in the menu bar will be automatically terminated if the user removes the extra from the menu bar." They recommend LSUIElement=true for menu-bar-only apps and `.menuBarExtraStyle(.window)` for richer controls. The init(isInserted:) variants exist.  (https://developer.apple.com/documentation/swiftui/menubarextra)
- [verified] MenuBarExtra has no first-party API to get or set the presentation state, disable the item, or reach its NSStatusItem or popup NSWindow, still as of Xcode 26. MenuBarExtraAccess adds `.menuBarExtraAccess(isPresented:isEnabled:)` and `.introspectMenuBarExtraWindow`. With `.menu` style the open menu blocks the runloop.  (https://raw.githubusercontent.com/orchetect/MenuBarExtraAccess/main/README.md)
- [verified] Since macOS 14, the Settings scene can be opened only via SettingsLink or the `openSettings` environment action (public in Xcode 16, back-deployed to macOS 14). A `.window` MenuBarExtra that needs to activate the app, open Settings and dismiss itself needs workarounds such as orchetect/SettingsAccess.  (https://raw.githubusercontent.com/orchetect/SettingsAccess/main/README.md)
- [likely] macOS 26 Tahoe added System Settings > Menu Bar > "Allow in the Menu Bar" per-app toggles, which can hide a third-party menu bar item.  (https://forum.parallels.com/threads/asking-how-to-keep-toolbox-icon-on-menu-bar.369594/post-946373; https://appleinsider.com/inside/macos-tahoe/tips/inside-control-center-on-macos-tahoe)
- [verified] SMAppService (macOS 13+) provides `.mainApp`, `register()`, `unregister()`, `status` (.enabled, .requiresApproval, .notRegistered, .notFound) and `openSystemSettingsLoginItems()`. register() on mainApp makes the app launch at subsequent logins.  (https://developer.apple.com/documentation/servicemanagement/smappservice; https://developer.apple.com/documentation/servicemanagement/smappservice/register())
- [verified] Apple DTS (Quinn) on SMAppService: sign the app and any helper "with the same Apple-issued code-signing identity. If, for example, you are using ad hoc signing (Sign to Run Locally in Xcode parlance) then you will see problems like this" (background permission lost across restarts). So mainApp registration with ad-hoc signing is not reliable.  (https://developer.apple.com/forums/thread/799910)
- [verified] Apple: on Apple-silicon Macs "the operating system enforces that any executable must be signed before it's allowed to run... a simple ad-hoc signature is sufficient". The toolchain ad-hoc signs at link time. Tools that change a binary after linking (strip, install_name_tool) require a manual codesign. Ad-hoc signatures "cannot pass through Gatekeeper".  (https://developer.apple.com/documentation/macos-release-notes/macos-big-sur-11_0_1-universal-apps-release-notes)
- [verified] Apple (Aug 6, 2024): "In macOS Sequoia, users will no longer be able to Control-click to override Gatekeeper when opening software that isn't signed correctly or notarized. They'll need to visit System Settings > Privacy & Security".  (https://developer.apple.com/news/?id=saqachfa)
- [likely] User steps on macOS 15/26/27 for a downloaded ad-hoc app:
1. Open the app. The dialog says "Apple could not verify 'SpeakerLink' is free of malware..." with Done and Move to Trash buttons.
2. Open System Settings > Privacy & Security and scroll to Security.
3. Click Open Anyway, which is shown for about an hour after the attempt.
4. Confirm Open and enter an admin password. The app is saved as an exception.

The alternative is `xattr -dr com.apple.quarantine /Applications/SpeakerLink.app`. No macOS 26 or 27 change to this flow was found.  (https://support.apple.com/en-us/102445 (via search snippets; host blocked); https://github.com/hovancik/stretchly (README); https://github.com/Aharoni-Lab/Miniscope-DAQ-QT-Software (README))
- [likely] Gatekeeper first-run checks normally apply only to quarantined files (browser downloads, AirDrop, Homebrew casks). Locally built or scp-transferred ad-hoc apps run without the prompt.  (https://developer.apple.com/forums/thread/740680; https://eclecticlight.co/2024/08/10/gatekeeper-and-notarization-in-sequoia/ (via search snippet))
- [verified] Notarization: zip with `ditto -c -k --keepParent`. Submit with `xcrun notarytool submit <zip> --wait` (keychain-profile, Apple ID or API key auth). Staple with `xcrun stapler staple <App.app>`; zips cannot be stapled, so re-zip after stapling. altool stopped working on 2023-11-01. Limit is 75 notarizations per day. Developer ID builds need `--timestamp` and `-o runtime`.  (https://developer.apple.com/documentation/security/customizing-the-notarization-workflow; https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac)
- [verified] API-key notarization in GitHub Actions: `xcrun notarytool submit file.zip --key AuthKey.p8 --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID" --wait --timeout 20m --output-format json`. Parse `status` (must be Accepted) and `id` with `plutil -extract`, and on failure run `notarytool log <id>`. Verify with `codesign --verify --deep --strict`, `stapler validate` and `spctl --assess --type execute`.  (https://raw.githubusercontent.com/artemnovichkov/Octavo/main/.github/workflows/release.yml)
- [verified] GitHub docs keychain recipe: `security create-keychain -p`, `set-keychain-settings -lut 21600`, `unlock-keychain`, `import $P12 -P "$P12_PASSWORD" -A -t cert -f pkcs12 -k`, `set-key-partition-list -S apple-tool:,apple: -k`, `list-keychain -d user -s`. Example secret names: BUILD_CERTIFICATE_BASE64, P12_PASSWORD, KEYCHAIN_PASSWORD. Encode with `base64 -i cert.p12 | pbcopy`. macOS provisioning profiles use `.provisionprofile`, but a plain Developer ID app without restricted entitlements needs none.  (https://github.com/github/docs/blob/main/content/actions/how-tos/deploy/deploy-to-third-party-platforms/sign-xcode-applications.md)
- [verified] Homebrew 5.0.0 (2025-11-12): casks without codesigning are deprecated, Homebrew/homebrew-cask casks that fail Gatekeeper are disabled in September 2026, and `--no-quarantine`/`--quarantine` are deprecated. Homebrew 6.0.0 (2026-06-11) repeated that this was on track.  (https://raw.githubusercontent.com/Homebrew/brew.sh/main/_posts/2025-11-12-homebrew-5.0.0.md; https://raw.githubusercontent.com/Homebrew/brew.sh/main/_posts/2026-06-11-homebrew-6.0.0.md)
- [verified] Acceptable Casks (current): official macOS casks "must pass Homebrew's Gatekeeper checks and must not require System Integrity Protection or Gatekeeper to be disabled or bypassed". Homebrew applies quarantine attributes to cask downloads. Notability for a GitHub project: at least 30 forks / 30 watchers / 75 stars, or 90 / 90 / 225 for a self-submission, and a repo under 30 days old is normally ineligible.  (https://raw.githubusercontent.com/Homebrew/brew/main/docs/Acceptable-Casks.md; https://raw.githubusercontent.com/Homebrew/brew/main/docs/Homebrew-Security-and-Supply-Chain.md; https://raw.githubusercontent.com/Homebrew/brew/main/docs/Package-Acceptance-Policy.md)
- [verified] Homebrew maintainers say the Gatekeeper policy covers only the main Homebrew/cask tap: "By default, we don't even check signing status on third-party taps" and "Anyone can create their own Tap". Reports say about 600 casks were disabled on 2026-09-01.  (https://github.com/orgs/Homebrew/discussions/6482; https://github.com/orgs/Homebrew/discussions/6334)
- [verified] Tap trust (Homebrew 6.0.0+): non-official taps need explicit trust. `brew install --cask user/repository/cask` trusts only that cask. `brew trust --cask user/repository/cask` or `brew trust user/repository` grant trust for short-name installs. HOMEBREW_NO_REQUIRE_TAP_TRUST is deprecated. Casks go in `Casks/` in a repo named `homebrew-<name>`, and cask tokens must be globally unique.  (https://raw.githubusercontent.com/Homebrew/brew/main/docs/Tap-Trust.md; https://raw.githubusercontent.com/Homebrew/brew/main/docs/How-to-Create-and-Maintain-a-Tap.md)
- [verified] Homebrew 7.0.0 (2026-09-13; 7.0.9 released Oct 8) deprecates third-party cask flight blocks in favour of `*_steps`, with removal on 2027-12-11. The minimum macOS uses the bare symbol `depends_on macos: :sonoma`. Intel macOS is Tier 3. macOS 27 on Apple Silicon is Tier 1.  (https://raw.githubusercontent.com/Homebrew/brew.sh/main/_posts/2026-09-13-homebrew-7.0.0.md; https://raw.githubusercontent.com/Homebrew/brew/main/docs/Cask-Cookbook.md; https://github.com/Homebrew/brew/releases)
- [uncertain] Third-party taps strip quarantine with `postflight_steps do run "/usr/bin/xattr", args: ["-dr", "com.apple.quarantine", "{{appdir}}/App.app"] end`. Whether this keeps working under Homebrew 7's cask sandbox and its quarantine changes (PR #23628) is not confirmed.  (https://github.com/YONN2222/cmdX/blob/main/Casks/cmdx.rb; https://github.com/romankurnovskii/homebrew-awesome-brew/blob/main/Casks/mead.rb; https://github.com/Homebrew/brew/releases)
- [verified] Core Audio: `AudioObjectPropertyListenerBlock` imports as the non-Sendable `(UInt32, UnsafePointer<AudioObjectPropertyAddress>) -> Void`. The inDispatchQueue parameter means: blocks are dispatched async on that queue, except IO-context properties (kAudioDevicePropertyDeviceIsRunning, kAudioDeviceProcessorOverload), which are dispatched synchronously. NULL means the block "will be directly invoked". The queue and block are retained until a matching AudioObjectRemovePropertyListenerBlock call with the same queue and block.  (https://developer.apple.com/documentation/coreaudio/audioobjectaddpropertylistenerblock(_:_:_:_:); https://developer.apple.com/documentation/coreaudio/audioobjectpropertylistenerblock)
- [verified] In Swift 6 mode, SE-0423 adds runtime actor-isolation checks when C/ObjC code calls isolated Swift code. A @MainActor closure or method invoked off the main queue traps in `_dispatch_assert_queue_fail` with no compile-time warning. Fixes: hand the API `.main` as its queue, or make the callback nonisolated/@Sendable and hop to the main actor.  (https://github.com/swiftlang/swift-evolution/blob/main/proposals/0423-dynamic-actor-isolation.md; https://forums.swift.org/t/crash-when-running-in-swift-6-language-mode/72431; https://hackernoon.com/swift-6-gave-me-zero-warnings-but-two-objective-c-callbacks-still-crashed-at-runtime)
- [verified] IOBluetooth is not thread-safe. An Apple staff engineer (Mar 2022) wrote: "IOBluetooth is not, and never been thread safe. Everything needs to be handled in the main thread."  (https://developer.apple.com/forums/thread/701643)
- [verified] IOBluetooth API details:
- `openConnection(_ target: Any!)` is async when a target is given and calls `-connectionComplete:status:` on it. `openConnection()` and `closeConnection()` are synchronous.
- `pairedDevices() -> [Any]!` is not per-user.
- `register(forConnectNotifications:selector:)` uses ObjC target/selector.
- The `IOBluetoothDeviceAsyncCallbacks` protocol has three required methods: connectionComplete, remoteNameRequestComplete, sdpQueryComplete.
- None of these carry isolation or Sendable annotations.  (https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice/openconnection(_:); https://developer.apple.com/documentation/iobluetooth/iobluetoothdeviceasynccallbacks; https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.0.sdk/System/Library/Frameworks/IOBluetooth.framework/Versions/A/Headers/objc/IOBluetoothDevice.h)
- [likely] NSBluetoothAlwaysUsageDescription "is required if your app uses the device's Bluetooth interface". On macOS, IOBluetooth access is gated by TCC (kTCCServiceBluetoothAlways).  (https://developer.apple.com/documentation/bundleresources/information-property-list/nsbluetoothalwaysusagedescription; https://developer.apple.com/forums/thread/738748)
- [verified] PackageDescription APIs:
- `swiftLanguageMode` (6.0)
- `treatAllWarnings(as:)`, `treatWarning(_:as:)`, `defaultIsolation(_:)`, `strictMemorySafety()` (6.2)

`treatAllWarnings(as: .error)` lowers to `-warnings-as-errors`. SE-0443 (-Werror <group>/-Wwarning) shipped in Swift 6.1, SE-0466 default isolation in 6.2, SE-0461 nonisolated(nonsending) in 6.2, and SE-0522 `@diagnose` in 6.4.  (https://github.com/swiftlang/swift-package-manager/blob/main/Sources/Runtimes/PackageDescription/BuildSettings.swift; https://github.com/swiftlang/swift-evolution/blob/main/proposals/0443-warning-control-flags.md; https://github.com/swiftlang/swift-evolution/blob/main/proposals/0466-control-default-actor-isolation.md)
- [verified] Real SwiftPM-only menu-bar app packagers to copy from:
- steipete/CodexBar Scripts/package_app.sh: per-arch builds plus lipo, Info.plist heredoc with LSUIElement, CODEXBAR_SIGNING=adhoc|identity, `codesign --force --timestamp --options runtime`, `xattr -cr`.
- steipete/RepoBar Scripts/package_app.sh and build_icon.sh: Icon Composer ictool, 824px art padded to 1024, sips, iconutil.
- microsoft/Zoomit-Mac Scripts/build-app.sh: universal build, --show-bin-path, iconset, ad-hoc sign with an identifier-based designated requirement.  (https://raw.githubusercontent.com/steipete/CodexBar/main/Scripts/package_app.sh; https://raw.githubusercontent.com/steipete/RepoBar/main/Scripts/package_app.sh; https://raw.githubusercontent.com/steipete/RepoBar/main/Scripts/build_icon.sh)
- [likely] An ad-hoc signature has a cdhash-based default designated requirement, and TCC tracks privacy grants such as Bluetooth by that requirement. Each rebuild or update therefore looks like a new app to TCC. Signing with `--requirements '=designated => identifier "<bundle id>"'` keeps grants stable across ad-hoc builds (the ZoomIt-Mac approach).  (https://raw.githubusercontent.com/microsoft/Zoomit-Mac/main/Scripts/build-app.sh; https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac)
- [likely] macOS 26 Tahoe is the last release for Intel Macs, and macOS 27 (released Sept 2026, 27.0.1 is 26A434) is Apple-silicon only. Rosetta 2 stays general-purpose through macOS 27. A universal binary still matters for Intel Macs that stay on macOS 14 to 26.  (https://petapixel.com/2025/06/10/macos-26-tahoe-is-the-end-of-the-road-for-intel-macs/; https://raw.githubusercontent.com/Homebrew/brew/main/docs/Acceptable-Casks.md; https://raw.githubusercontent.com/actions/runner-images/main/images/macos/xcode-27-arm64-Readme.md)

# SNIPPETS
// Package.swift (tools 6.2 = Swift 6 language mode; needs Xcode 26.x Swift 6.2+). Pattern from microsoft/Zoomit-Mac + PackageDescription 6.2 API
// swift-tools-version: 6.2
import PackageDescription
let package = Package(
  name: "SpeakerLink",
  platforms: [.macOS(.v14)],
  targets: [
    .target(name: "SpeakerLinkCore"),                       // pure logic, unit-testable
    .executableTarget(
      name: "SpeakerLink",
      dependencies: ["SpeakerLinkCore"],
      exclude: ["Info.plist"],                              // avoid 'unhandled file' warning; script copies it
      swiftSettings: [ /* optional: .treatAllWarnings(as: .error) */ ]
    ),
    .testTarget(name: "SpeakerLinkCoreTests", dependencies: ["SpeakerLinkCore"])
  ]
)

---
# Info.plist for a menu-bar-only app (keys from Apple docs; layout as in CodexBar/ZoomIt scripts)
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleExecutable</key><string>SpeakerLink</string>
  <key>CFBundleIdentifier</key><string>io.github.cyrus-sch.speakerlink</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleName</key><string>SpeakerLink</string>
  <key>CFBundleDisplayName</key><string>SpeakerLink</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
  <key>NSHumanReadableCopyright</key><string>GPL-3.0-or-later</string>
  <key>NSBluetoothAlwaysUsageDescription</key><string>SpeakerLink connects and disconnects your paired Bluetooth headphones and speakers.</string>
</dict></plist>

---
#!/usr/bin/env bash
# Scripts/package-app.sh — universal build + .app + icon + ad-hoc sign (pattern: microsoft/Zoomit-Mac, steipete/CodexBar)
set -euo pipefail
APP=SpeakerLink; BUNDLE_ID=io.github.cyrus-sch.speakerlink; MIN=14.0
VERSION=${VERSION:-0.0.0}; BUILD=${BUILD:-1}
ARGS=(-c release --arch arm64 --arch x86_64)
# Only needed on Swift 6.4/swiftbuild (sdk-stamp bug):
# ARGS+=(-Xlinker -platform_version -Xlinker macos -Xlinker "$MIN" -Xlinker "$(xcrun --sdk macosx --show-sdk-version)")
swift build "${ARGS[@]}"
BIN="$(swift build "${ARGS[@]}" --show-bin-path)/$APP"   # Swift 6.3: .build/apple/Products/Release
OUT=dist/$APP.app; rm -rf "$OUT"; mkdir -p "$OUT/Contents/MacOS" "$OUT/Contents/Resources"
cp "$BIN" "$OUT/Contents/MacOS/$APP"
cp Sources/$APP/Info.plist "$OUT/Contents/Info.plist"
plutil -replace CFBundleShortVersionString -string "$VERSION" "$OUT/Contents/Info.plist"
plutil -replace CFBundleVersion -string "$BUILD" "$OUT/Contents/Info.plist"
ICONSET="$(mktemp -d)/AppIcon.iconset"; mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z $s $s Assets/icon-1024.png --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) Assets/icon-1024.png --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$OUT/Contents/Resources/AppIcon.icns"
xattr -cr "$OUT"
if [[ -z "${SIGN_IDENTITY:-}" ]]; then
  codesign --force --sign - --requirements "=designated => identifier \"$BUNDLE_ID\"" "$OUT"
else
  codesign --force --timestamp --options runtime --sign "$SIGN_IDENTITY" "$OUT"
fi
codesign --verify --strict --verbose=2 "$OUT"
lipo -archs "$OUT/Contents/MacOS/$APP"                 # expect: x86_64 arm64
vtool -arch arm64 -show-build "$OUT/Contents/MacOS/$APP" # expect minos 14.0, sdk == installed SDK (26.x)
plutil -lint "$OUT/Contents/Info.plist"
ditto -c -k --keepParent "$OUT" "dist/$APP-$VERSION.zip"

---
# .github/workflows/release.yml (labels/paths from runner-images; steps adapted from artemnovichkov/Octavo)
name: Release
on: { push: { tags: ['v*'] } }
permissions: { contents: write }
jobs:
  mac:
    runs-on: macos-26            # arm64, Xcode 26.6 default (Swift 6.3)
    env: { DEVELOPER_DIR: /Applications/Xcode_26.6.app/Contents/Developer }
    steps:
      - uses: actions/checkout@v7
      - run: swift --version && xcodebuild -version
      - run: swift build -Xswiftc -warnings-as-errors && swift test
      - name: Import Developer ID cert (optional)
        if: ${{ secrets.DEVELOPER_ID_P12_BASE64 != '' }}   # (use an env-var check in practice; secrets cannot be used directly in if)
        env: { P12_BASE64: '${{ secrets.DEVELOPER_ID_P12_BASE64 }}', P12_PASSWORD: '${{ secrets.DEVELOPER_ID_P12_PASSWORD }}' }
        run: |
          kc="$RUNNER_TEMP/signing.keychain-db"; kp="$(uuidgen)"
          echo "$P12_BASE64" | base64 --decode > "$RUNNER_TEMP/cert.p12"
          security create-keychain -p "$kp" "$kc"
          security set-keychain-settings -lut 21600 "$kc"
          security unlock-keychain -p "$kp" "$kc"
          security import "$RUNNER_TEMP/cert.p12" -k "$kc" -P "$P12_PASSWORD" -T /usr/bin/codesign
          security set-key-partition-list -S apple-tool:,apple: -s -k "$kp" "$kc" >/dev/null
          security list-keychains -d user -s "$kc" $(security list-keychains -d user | tr -d '"')
          echo "SIGN_IDENTITY=$(security find-identity -v -p codesigning "$kc" | sed -n 's/.*"\(Developer ID Application: .*\)"/\1/p' | head -1)" >> "$GITHUB_ENV"
      - run: VERSION="${GITHUB_REF_NAME#v}" BUILD="$GITHUB_RUN_NUMBER" ./Scripts/package-app.sh
      - name: Notarize + staple (only when signed)
        if: env.SIGN_IDENTITY != ''
        env: { ASC_KEY_ID: '${{ secrets.ASC_KEY_ID }}', ASC_ISSUER_ID: '${{ secrets.ASC_ISSUER_ID }}', ASC_KEY_P8: '${{ secrets.ASC_KEY_P8 }}' }
        run: |
          key="$RUNNER_TEMP/AuthKey.p8"; printf '%s\n' "$ASC_KEY_P8" > "$key"
          auth=(--key "$key" --key-id "$ASC_KEY_ID" --issuer "$ASC_ISSUER_ID")
          ditto -c -k --keepParent dist/SpeakerLink.app "$RUNNER_TEMP/n.zip"
          r="$(xcrun notarytool submit "$RUNNER_TEMP/n.zip" "${auth[@]}" --wait --timeout 20m --output-format json)"; echo "$r"
          [[ "$(echo "$r" | plutil -extract status raw -)" == Accepted ]] || { xcrun notarytool log "$(echo "$r" | plutil -extract id raw -)" "${auth[@]}"; exit 1; }
          xcrun stapler staple dist/SpeakerLink.app
          rm dist/*.zip && ditto -c -k --keepParent dist/SpeakerLink.app "dist/SpeakerLink-${GITHUB_REF_NAME#v}.zip"
          spctl --assess --type execute --verbose=2 dist/SpeakerLink.app
      - run: cd dist && shasum -a 256 *.zip > SHA256SUMS.txt
      - run: gh release create "$GITHUB_REF_NAME" --generate-notes --verify-tag dist/*.zip dist/SHA256SUMS.txt
        env: { GH_TOKEN: '${{ github.token }}' }
      - if: always()
        run: security delete-keychain "$RUNNER_TEMP/signing.keychain-db" 2>/dev/null || true

---
// Swift 6: Core Audio listener that cannot trip SE-0423 runtime isolation checks (API shape from Apple CoreAudio docs)
import CoreAudio
@MainActor final class DeviceListWatcher {
  private var address = AudioObjectPropertyAddress(
    mSelector: kAudioHardwarePropertyDevices,
    mScope: kAudioObjectPropertyScopeGlobal,
    mElement: kAudioObjectPropertyElementMain)          // not the deprecated ...ElementMaster
  private var block: AudioObjectPropertyListenerBlock?   // keep: removal needs the same block + queue
  var onChange: () -> Void = {}
  func start() {
    let b: AudioObjectPropertyListenerBlock = { [weak self] _, _ in self?.onChange() } // main-actor-isolated closure
    block = b
    AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, DispatchQueue.main, b) // MUST be .main
  }
  func stop() {
    guard let b = block else { return }
    AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, DispatchQueue.main, b)
    block = nil
  }
}
// Alternative for any queue: build the block in a `nonisolated static func` as `@Sendable { _, _ in Task { @MainActor in ... } }`
// and never let UnsafePointer<AudioObjectPropertyAddress> escape the block (not Sendable).

---
// Swift 6: IOBluetooth on the main actor (IOBluetooth is main-thread only per Apple staff; protocol has 3 required methods)
import IOBluetooth
@MainActor final class BluetoothConnector: NSObject, @preconcurrency IOBluetoothDeviceAsyncCallbacks {
  func paired() -> [IOBluetoothDevice] { (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? [] }
  func connect(_ d: IOBluetoothDevice) -> IOReturn { d.openConnection(self) }   // async; never call sync openConnection() on main
  func connectionComplete(_ device: IOBluetoothDevice!, status: IOReturn) { /* update UI state */ }
  func remoteNameRequestComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}
  func sdpQueryComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}
}

---
# Homebrew personal tap: repo cyrus-sch/homebrew-tap, file Casks/speakerlink.rb (DSL per Cask Cookbook, Homebrew 7)
cask "speakerlink" do
  version "1.0.0"
  sha256 "<sha256 of zip>"
  url "https://github.com/cyrus-sch/speakerlink-mac/releases/download/v#{version}/SpeakerLink-#{version}.zip"
  name "SpeakerLink"
  desc "Play the same audio on several Bluetooth headphones and speakers"
  homepage "https://github.com/cyrus-sch/speakerlink-mac"
  depends_on macos: :sonoma
  app "SpeakerLink.app"
  zap trash: ["~/Library/Preferences/io.github.cyrus-sch.speakerlink.plist"]
  caveats "If unsigned: open once, then System Settings > Privacy & Security > Open Anyway (or xattr -dr com.apple.quarantine /Applications/SpeakerLink.app)."
end
# user: brew install --cask cyrus-sch/tap/speakerlink   (fully qualified => trusts only this cask under tap trust)

---
// Login item (macOS 13+): SMAppService.mainApp — expect flakiness when ad-hoc signed (Apple DTS)
import ServiceManagement
@MainActor func setLaunchAtLogin(_ on: Bool) throws {
  if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
  if SMAppService.mainApp.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems() }
}

---

# RISKS
- Scope conflict: the user asked to move the Mac app to its own repo, so it should not be added to the existing project. The CI, bundle ID, release URLs and tap above assume a standalone repo with Package.swift at the root, for example cyrus-sch/speakerlink-mac. The orchestrator should create or choose that repo; this step did not.
- If CI moves to Xcode 27 / Swift 6.4 (the xcode-27 label, or macos-26 once 27 becomes default), the swiftbuild SDK-stamp bug can silently ship the legacy UI. Add a CI guard that compares `vtool -show-build` sdk with `xcrun --show-sdk-version`. Passing `-Xlinker -platform_version` alongside xcbuild's own flag in a multi-arch Swift 6.3 build is untested, so add it only when the guard fails.
- The xcode-27 runner is a preview with queueing and capacity caveats. Pin `macos-26` plus Xcode 26.6 for releases. Manifests using swift-tools-version 6.2+ will not build on macos-15, whose default Xcode 16.4 has Swift 6.1.
- A universal build needs full Xcode (xcbuild), which the runners have. The Swift 6.3 output path `.build/apple/Products/Release` differs from Swift 6.4's, so always use `--show-bin-path`.
- With ad-hoc signing, macOS cannot pass Gatekeeper. Every user must use Open Anyway (admin password) or xattr. TCC Bluetooth grants reset on every update unless an identifier-based designated requirement is used. SMAppService.mainApp may lose its state (per DTS). Developer ID costs USD 99/year and is the only way to get a clean first launch and an official Homebrew cask.
- Homebrew: official homebrew/cask rejects non-notarized apps (enforced since 2026-09-01) and needs 225 stars or 90 forks or 90 watchers for a self-submission. A personal tap works, but users still get quarantine. Removing quarantine in `postflight_steps` may be blocked by the Homebrew 7 sandbox and is arguably against the spirit of Homebrew policy, so prefer caveats.
- App Translocation: a quarantined app launched from ~/Downloads runs from a randomized read-only path. A login item registered from there would be broken, so tell users to move the app to /Applications before first launch. This is from general knowledge and was not re-verified here.
- MenuBarExtra (.window) cannot be opened or closed programmatically without MenuBarExtraAccess (MIT, compatible with GPL-3.0). On macOS 26 the user can hide the item via 'Allow in the Menu Bar', and a menu-bar-only app is terminated if its extra is removed. Provide a fallback, such as handling app reopen to show a window.
- Swift 6 runtime traps (SE-0423) give no compile-time warning. Every C, Core Audio or IOBluetooth callback must either run on main or be explicitly nonisolated. An unneeded `@preconcurrency import` itself triggers a warning, which breaks warnings-as-errors. Deprecated Core Audio constants (ElementMaster, VirtualMasterVolume) also produce warnings.
- Several Apple hosts (support.apple.com, docs.swift.org, brew.sh, eclecticlight.co) were unreachable from this sandbox. The exact Gatekeeper dialog wording and the roughly one-hour window for 'Open Anyway' come from secondary sources and Apple text quoted in search snippets.

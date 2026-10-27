# Publishing updates

Website deployments on main produce a unique build ID, version.json and service worker cache. An open website checks once a minute and when it returns online or to the foreground. It saves state and reloads only on the home screen with no generation or text entry active. Listening is never interrupted. Offline users keep the cached version until connected.

## Android

Install version 1.1.0 once to enable update checks. Old APKs cannot gain an updater without this installation. Checks happen when the app opens on Home, at most once every six hours. The update dialog downloads the APK, checks SHA-256 and package/version, then opens Android installation confirmation. Android enforces matching signing identity. Users may need to allow SchoolHelp as an installation source. Nothing silently installs. Listening and generation suppress update prompts.

For every future Android release:

1. Increase versionCode and versionName in android/app/build.gradle.kts, then commit and push source.
2. Run android/scripts/Publish-Update.ps1 on this development computer. It builds/tests/lints and publishes a new GitHub release with APK and checksum. Keep the original Android signing key: this currently uses the existing development signing identity, matching previously distributed APKs. Do not build these updates on a different machine without transferring that key securely.
3. Commit and push web/public/android-update.json, which the script generates only after upload succeeds. The Pages deployment makes the update discoverable. Do not reuse release version tags or edit APKs after publishing.

A source edit alone does not produce an Android binary. The release step is required. Website source changes deploy automatically.

## Native iPhone

Native updates require signed builds distributed through TestFlight or the App Store; this source project cannot silently replace itself. The website installed on an iPhone home screen uses the web update mechanism above. No Apple distribution account has been purchased or configured here.

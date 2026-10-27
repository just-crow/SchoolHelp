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

## Speech compatibility in 1.1.1

Fish Audio publishes no preset supported_voices list in OpenRouter's Models API. The settings now offer Provider default voice (omit voice entirely), plus an optional custom Fish Audio reference voice ID. The app no longer blocks Fish Audio generation when voice is empty. Other providers still require explicit voices. Do not copy OpenAI voice names into Fish Audio settings. Gemini and HeyGen request PCM; other models request MP3. No paid provider generation was performed during verification; requests and audio handling are checked with mock responses.

Sources: https://openrouter.ai/docs/guides/overview/multimodal/tts and https://openrouter.ai/api/v1/models?output_modalities=speech (checked October 27, 2026).

## Download feedback in 1.1.2

Android update downloads now show a modal progress bar, percentage and downloaded/total MB (or downloaded MB if the server omits its size). The dialog starts at Connecting, then advances to Verifying update. A successful download opens Update ready to install, with an explicit Install update button or Allow updates permission guidance. After granting permission, returning to the app offers installation. Download errors stay visible with Retry, and Cancel removes the partial file. Completed verified downloads survive app recreation; interrupted downloads must be retried. Keep the app open while downloading. No Android installation screen is expected before the APK finishes downloading and verification. This UI becomes available after installing 1.1.2; older installed builds retain their old download UI during that first upgrade.

## Spoken language in 1.1.3

The answer model supplies a new optional per-problem spokenLabel, with page and question numbers spelled out in the main question's language. Playback uses it verbatim instead of adding English or Polish prefixes according to app settings. All answer, placement and retake instructions are prompted in that same language. Rewrites preserve localized labels, and Android session/sharing JSON and web/iOS Codable preserve the field. Older solutions without spokenLabel use only the question number, without an English prefix. Already generated audio cannot be changed by this update; generate a new recording to hear the correction. Local voice choice and provider voice IDs still need to support the question language. iOS tests are provided but unrun without Xcode.

## Manual output language in 1.1.4

Before solving, enter Answer and speech language on Home. This saved free-text field controls all generated answer text, page/question announcements and instructions. It replaces automatic output-language detection; foreign words are retained only when essential to the task. Existing English/Polish controls are now explicitly labeled Offline voice language and only select downloaded speech models. Choose a compatible OpenRouter voice for other languages. Changing the language requires solving again; saved recordings or retrying speech from old answers does not translate them. Android preferences, browser storage and iOS settings preserve the entry. Native iOS changes and tests remain unrun without Xcode.

## Optional context in 1.1.5

Home now has a multiline Context for the AI field before photo capture/import. Supply background, definitions or task instructions, up to 8,000 characters. The note is saved locally until cleared and sent with every new solve to the chosen vision engine, including local inference. It is user content, separate from the system response contract and the page images. Speech requests receive only the generated answer script, not the raw note. Clear context before unrelated work. Editing context requires solving again; retrying speech from an existing answer does not re-solve it. Native iOS source is updated but remains untested without Xcode.

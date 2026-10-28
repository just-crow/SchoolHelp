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

## One generation button in 1.1.6

The existing Solve & prepare speech button is now always displayed, enabled when there are photos, nonblank context, or both (unless already processing). No second generation button was added. Context-only is a text task for the selected answer model and then uses the same speech, listening, persistence and sharing pipeline. Empty requests are rejected before network calls. Photo requests still combine the context and all ordered pages. Local engines use their existing downloaded multimodal model for text-only inference; browser offline quality remains experimental.

## Slower speech in 1.1.7

Minimum speed is now 0.25×, with 0.05 slider increments. Android persists the lower setting instead of clamping it to 0.7×. OpenRouter recordings use the selected playback speed directly. For Android offline speech below 0.7×, synthesis stays at 0.7× and playback applies only the remaining slowdown, avoiding double slowdown and unusually slow synthesis. Shared recordings preserve this playback multiplier. Browser and iOS playback also accept 0.25×. Android speed changes retain their existing behavior of applying to the next prepared recording. The website also adjusts its loaded audio immediately when the slider moves.

## Writing pauses in 1.1.8

Enable Settings → Writing pauses and enter writing speed (1–300 words/minute; default 30). The answer model supplies optional speechSegments with text, writingText and pauseSeconds. Ordinarily each question is one segment. The app counts only the writingText and inserts ceil(words × 60 / writingWpm) seconds of silence after it. A missing segment list falls back to one pause per question, based on its answer text. Labels and spoken instructions are not counted. Word counts and writing-time estimates are approximate.

Context can request grouping (for example “pause after each table row”) and explicit timing (“pause twenty seconds after every answer”, “no pause after explanations”). The model represents a fixed override in pauseSeconds; null uses the writing-speed calculation and zero skips a pause. These requests require solving again to update segment metadata. The settings and segment metadata survive session saves; raw context is not appended to speech.

Speech is generated in ordered clips and assembled into one AAC/M4A recording containing actual silence, including after the final answer. This preserves screen-off playback, seek/earbud controls, session positions and existing shared queues. Silence is scaled for the selected playback multiplier when prepared so a slower voice does not also lengthen the configured writing pause. Reprepare when changing speed: changing browser playback speed on an already prepared recording also changes its silence duration.

Limits: 10 minutes per individual pause, 100 speech parts, two hours total writing silence. Part-by-part speech can take longer and uses separate provider requests; failures are shown and paid retries remain explicit. Temporary clips are removed after assembly or cancellation. Turning Writing pauses off preserves the original single-request speech path. Offline local models may omit segment metadata; standard per-question pauses still work, but context-specific grouping depends on model compliance.

Verification: Android build/unit tests/lint passed; a real Android emulator export test passed. Its two 0.5-second tone clips plus speed-compensated gaps produced a roughly 3-second AAC file with two independently detected silent intervals, including the final pause. Browser FFmpeg/WASM verification produced 3.000 seconds and verified both silent regions from decoded samples. Browser settings toggle and writing-speed persistence were verified. Web unit tests pass. iOS source and tests are provided; Xcode/Apple hardware verification remains unrun. No paid provider speech call was made during tests.

## Crash investigation and safeguards in 1.1.9

Reported symptom: the Android app sometimes closes while preparing OpenRouter speech/writing pauses on a Xiaomi 14 Pro. No phone is connected by ADB, so its actual crash stack and OS exit reason have not yet been collected. Do not describe the phone's root cause as confirmed.

Code findings and changes:

- Foreground-service startup occurred outside the generation exception handler. Startup/promotion failures and cleanup failures are now caught and reported without terminating the UI. Preparation starts its service once, with media-processing type on Android 15+, instead of requesting another start when speech begins after a network response. Timeouts stop the service immediately, and wake-lock release is guarded.
- Writing-pause assembly and sharing uploads could run separate audio Transformers simultaneously. A process-wide lock now serializes all exports. Cancellation/release completes on the main looper before the next export gets the lock or temporary clips are removed.
- Local diagnostics record the preparation/export stage and safe exception stack (class names and stack frames only, without exception messages). The standard OS crash handler still runs normally. Settings → Copy crash report includes these local reports and up to ten application process-exit summaries on Android 11+. No telemetry upload, SDK, API key, question text or photo is included. The Settings footer now displays the real build version.

Verification: Android build/unit tests/lint passed. Emulator tests passed for 100 alternating MP3/WAV clips, overlapping assembly/sharing export requests, four cancellation/retry cycles, and the original final-writing-pause duration test. No spontaneous crash appeared in emulator crash logs during these tests. An explicitly injected emulator crash was captured by the local handler and preserved a safe stack. That injected crash is diagnostic verification, not a reproduction of the user's Xiaomi failure. No paid OpenRouter requests were used. If the phone still closes, collect its Copy crash report after updating to distinguish an app exception, native codec failure, ANR, or OS process termination.

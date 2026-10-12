# Verified on 8 October 2026

## Build

- Windows project-local Microsoft JDK 17.0.18, Gradle 8.13, Android SDK 36, Android Gradle Plugin 8.13.2.
- Debug APK and instrumentation APK compile successfully.
- Android lint passes with no errors. Remaining warnings are dependency-update notices and optional Kotlin/storage API style suggestions.
- The Windows setup/build scripts were executed successfully.

## Automated tests

43 JVM tests passed:

- 10 tap/seek tests, including delayed single/double actions, triple-tap exclusivity, holds/cancellation, timing boundaries, and seek clamping.
- 15 answer parsing and spoken-text tests, including table placement instructions with reasoning disabled, English/Polish fraction cleanup, nested fraction grouping, and notation validation.
- 9 OpenRouter tests against a local mock HTTP server, including catalog/authentication, pasted null/zero-width padding, and one-time listening-script rewrites. Tests verify that repeated notation is blocked and plain scripts do not cause an extra request.
- 1 stalled-response cancellation test confirming that cancellation closes the network socket promptly.
- 8 OpenRouter speech tests cover catalog parsing, model/voice request parameters, MP3 storage, Gemini PCM-to-WAV conversion, safe provider error details, missing selections, failed/invalid responses, and cancellation cleanup. Standard JVM tests use a local mock server.

5 Android instrumentation tests passed on a Pixel 6 profile running an Android 15 / API 35 x86_64 emulator:

- Home/settings navigation and provider selection.
- Listening begins paused, exposes an accessible play/pause action, keeps the display awake, and restores brightness/awake flags on exit.
- Physical single/double/triple taps control playback correctly; holding exits listening mode.
- Kokoro English generates a valid, seekable WAV recording using the actual native speech engine.
- Piper Polish generates a valid, seekable WAV recording using the actual native speech engine.

The voice tests used the exact checksum-verified model packages listed in `ModelCatalog`. UI screenshots were captured and visually reviewed; they are in `docs/screenshots`.

After adding OpenRouter speech, four emulator UI tests passed: the three app smoke tests above and a new test that chooses a speech model/voice independently of the answer engine, verifies persisted settings, and switches back to offline voices. The speech picker test uses a fixed catalog fixture. The live public speech catalog was separately fetched successfully, including its supported voice IDs.

The Gemini format failure was reproduced live: OpenRouter returned HTTP 400 stating that Gemini TTS supports PCM rather than MP3. After the fix, an explicitly opted-in live test generated a short phrase with `google/gemini-3.8-flash-lite-tts` and voice `Zephyr`, wrapped the returned PCM as WAV, and Android's media reader confirmed a playable duration of 2,880 ms. All three app smoke tests passed again. The live test uses the saved key inside Android and is skipped by default; other speech providers have not been tested with paid requests.

After updating hold controls, all four app smoke tests passed. A hold restarts playback at 1.5 seconds; releasing then cancels termination. Continuing to 3 seconds ends the session, clears image/answer/recording state, deletes the app's imported photo copy, stops playback, and restores display brightness and timeout flags. The source photo remains untouched. These checks use real held pointer input and a real imported test image; the existing single/double/triple tap checks still pass. Results are in `hold-controls-device-results.txt`.

The Android-only fraction regex crash is fixed by removing regex-based parsing of TeX groups. Two new Android runtime tests pass for plain text, frac/dfrac/tfrac, square roots, English/Polish fractions, and nested groups. The four app smoke tests passed in the same run: six Android tests total. Both solvers now request a single rewrite of missing or formatted spoken scripts and validate the result before TTS. See `notation-fix-device-results.txt`.

Bluetooth media controls were tested using Android's real system media-command dispatch. The test verifies next/previous seeking by five seconds while paused, play/pause toggling, explicit play/pause, and media-session deactivation after clearing. All five app smoke tests passed after this change; see `earbud-controls-device-results.txt`. Build, lint, and the 43 JVM tests passed. Physical Redmi Buds 8 Active gestures and available left/right assignments still require testing on the user's phone.

Multi-photo sessions passed build, lint, 44 JVM tests, and all six emulator app smoke tests. The new Android test imports two images in one operation, checks unique private copies, navigates pages, reorders and rotates the selected page, appends another photo, removes it, checks the ten-photo limit, and verifies session termination deletes every remaining private copy while retaining the source. A JVM test verifies combined page order, repeated question numbers, English/Polish spoken page labels, and unchanged single-page speech. See `multi-photo-device-results.txt`. Multiple pages are solved separately and combined into one recording; live multi-page AI requests and the system picker on the user's physical phone remain unverified.

After the report that earbud gestures did nothing, the foreground activity was connected to the native media controller. Foreground headset/media keys and media-session key events now share a handler that consumes key-up/repeat events without acting twice, and the listening screen displays the last received earbud command. All 44 JVM tests, lint, and seven emulator app smoke tests passed. The new instrumentation test injects headset-hook, play/pause, next/previous, and repeated key-down events through Android, starts a recording with the chime disabled, verifies controller binding and command feedback, and checks cleared recordings reject keys. The session-command test and all previous controls/multi-photo checks passed too. Results: `headset-foreground-device-results.txt`. This does not verify the user's physical earbuds or competition with other media apps; those remain device checks.

Joint-page solving and saved sessions replace the previous independent-page/temporary-session behavior. Both solvers now send one ordered message containing all labelled page images, with instructions to join continued questions/tables/passages/choices and return each question once with its starting page. Local inference configures the image count and a larger multi-page context. A mock HTTP test verifies one request, both image payloads in order, shared continuation instructions, and the joined response's starting page. This verifies request wiring, not live model answer accuracy.

Session photos and audio now use persistent private files, with atomic JSON metadata for transcription/answers, selected page, recording speed, and position. Completed answers are saved before TTS. Back from listening mode preserves the session; explicit end/clear deletes it. In-progress requests are not resumed after process death. Build, lint, all 45 JVM tests, and eight emulator app smoke tests passed; see `joint-pages-session-device-results.txt`. The new restoration test constructs a fresh ViewModel from persisted files and checks both photos, joined transcription, page selection, recording/speed/position, paused restoration, position updates, and deletion. The multi-photo test now verifies Back preserves state. Playback tests also cover accepting media commands during seek buffering and rejecting duplicate/repeat key-down events until release. Live multi-page model accuracy, local GPU inference, physical earbud routing, and full force-stop/relaunch acceptance still require device/account checks.

## Still requires a physical phone / account

- Gemma GPU initialization, image understanding, mathematical accuracy, latency, memory, and temperature on the user's phone. The real LiteRT-LM inference integration compiles, but a complete Gemma model was not downloaded into the emulator or benchmarked there.
- Camera capture and photo-picker behavior on the target phone.
- Real OpenRouter image-to-answer requests on additional models and full worksheets. The image solver's automated HTTP integration uses a mock server; the live account test above covers a short speech request only.
- Real calls, wired/Bluetooth disconnection, long paused screen-awake sessions, and voice quality through the intended headphones.

These limits do not affect the successful build and emulator checks above. Use `DEVICE_TESTING.md` for physical-device acceptance.


## 9 October: background playback and sharing

See [background/sharing verification](BACKGROUND_SHARING_VERIFICATION.md) for the current 46 JVM, 15 Android and 11 PostgreSQL checks, deployment requirements and remaining physical-device checks.

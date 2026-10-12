# SchoolHelp for iPhone

This is the native SwiftUI source project. It targets iOS 18 or later and uses the deployed SchoolHelp Supabase project plus the registered iOS app in Firebase project `schoolhelp-f47ed`. The package versions are pinned in `project.yml`. No private server credential is included.

## Build on a Mac

1. Install current Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen). In Terminal, run `brew install xcodegen` if Homebrew is available.
2. In this folder, run `python3 prepare-models.py` to download and verify the pinned English and Polish offline voices, then `xcodegen generate` and open `SchoolHelp.xcodeproj`.
3. Select the SchoolHelp target and your Apple development team in Signing & Capabilities. Build to an iPhone or iOS 18+ simulator. Simulator camera and remote push behavior differ from a phone.
4. Run `xcodebuild -project SchoolHelp.xcodeproj -scheme SchoolHelp -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO build test`, changing the simulator name if necessary.

`BackendConfig.json` contains only the public Supabase URL and publishable key. `GoogleService-Info.plist` is the public Firebase client configuration generated for `com.schoolhelp.app`. Both are included in the source project. The Supabase service role and Firebase service-account key stay on the server.

## Enable iPhone push

Apple push delivery needs an Apple Developer account. Create a signing team and APNs authentication key, enable Push Notifications and Background Modes for the iOS app ID `com.schoolhelp.app`, then upload the APNs key to Firebase project `schoolhelp-f47ed`. The source already requests notification permission, registers the APNs token with Firebase, sends its FCM token to the shared backend with platform `ios`, and syncs on background notifications. iOS may delay or suppress background launches; opening the app always reconciles the durable queue.

## Local models

Settings can download the pinned Gemma 4 E2B LiteRT-LM vision file with SHA-256 verification. `prepare-models.py` downloads the exact Android-pinned Kokoro English and Piper Polish archives, verifies them, and includes them in the iPhone app bundle. Settings also accepts an unpacked voice folder if you want to replace them. The sherpa-onnx adapter reads the ONNX model, tokens, voice file, and espeak data. Large models and inference are device-dependent; choose OpenRouter explicitly if a model cannot run on the iPhone. The app does not make a paid fallback request.

OpenRouter speech and local sherpa speech are converted to AAC/M4A before publication so Android and iPhone can exchange recordings. Listening continues with the screen locked through the iOS audio session and lock-screen media controls. Standard earbud next/previous commands seek five seconds.

## Acceptance on Apple hardware

Check camera/photo import, ordered pages, solving and speech, ready cue, touch holds, screen-off playback, interruptions, earbuds, and persistence. For sharing, test Android→iPhone and iPhone→Android, independent listener queues, owner leaving listening mode, offline recovery, late joins, removal, code rotation, and expiry. These device checks remain pending until a Mac and iPhone are available.

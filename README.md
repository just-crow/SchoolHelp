# SchoolHelp

SchoolHelp photographs school questions, prepares concise spoken answers, and shares text and audio between Android and iPhone using one Supabase backend. The Android app is Kotlin/Compose; the iPhone app is SwiftUI.

## Download for Android

[Download the latest Android APK](https://github.com/just-crow/SchoolHelp/releases/latest/download/SchoolHelp.apk). Open it on your Android phone and allow installation from your browser when prompted. Install over an existing SchoolHelp installation to preserve its saved data. This is a debug-signed preview build. The iPhone version currently requires building from source on a Mac.

## Folders

- `android/`: Android source, Gradle build, scripts, and debug APKs.
- `ios/`: SwiftUI source and an XcodeGen project specification.
- `web/`: installable browser version with photo solving, speech, and shared queues.
- `supabase/`: shared database migrations and the authenticated sharing Edge Function.
- `shared/fixtures/`: platform-neutral answer and queue examples.
- `docs/`: setup and verification notes.

## Android on Windows

```powershell
powershell -ExecutionPolicy Bypass -File .\android\scripts\Build.ps1
powershell -ExecutionPolicy Bypass -File .\android\scripts\Start-Emulator.ps1
```

The Android scripts use the ignored SDK and JDK under root `.tools/`. The configured debug APK is at `android/SchoolHelp-firebase-configured.apk`. Installing it over the previous build preserves app data and its anonymous sharing identity.

## iPhone

The [SchoolHelp website](https://just-crow.github.io/SchoolHelp/) also runs on iPhone and can be added to the Home Screen from Safari. It uses browser-compatible local models and the same sharing backend. Browser background behavior differs from the native app; see `web/README.md`.

See `ios/README.md`. The source targets iOS 18 or later and uses the same codes, anonymous Supabase identity, ordered M4A queue, and solution JSON as Android. An installable iPhone build requires macOS with Xcode. Apple push notifications and TestFlight require Apple developer signing and APNs setup. The iPhone project has not been compiled or run on Apple hardware from this Windows workspace.

OpenRouter requests use a user-supplied key stored on each device. Local Gemma and sherpa-onnx assets are optional downloads/imports. The app never silently changes a local selection into a paid OpenRouter request.

Update delivery and release instructions: [docs/UPDATES.md](docs/UPDATES.md).

# SchoolHelp website

The website reproduces the Android Home, Settings and Listening screens with the original colors, fonts and ready sound. It supports ten ordered camera/import photos, crop/rotate/reorder, questions across pages, listening-only answers, OpenRouter vision and speech model/voice selection, local inference, saved sessions, touch gestures, wake lock, Media Session and the same Supabase sharing codes and FIFO queues.

## Run and build

Use Node 22.12 or newer. Run `npm ci`, then `npm run build` and `npm run preview` in this folder. `npm run dev` starts development; run a build first to prepare the local FFmpeg WASM files. `npm test` verifies prompts, solution parsing, visual notation rejection, tap/hold timing, header cleaning and queue consumption against shared fixtures.

`.github/workflows/web.yml` builds/tests and publishes GitHub Pages. Browser assets and public backend configuration are shipped; server credentials and user API keys are never committed. GitHub Pages provides HTTPS for camera, wake lock and PWA installation. Add the website to the iPhone Home Screen from Safari's Share menu, or use Install in supported Android browsers.

## Native differences

Browsers cannot run the native LiteRT-LM/sherpa binaries. Local photo solving uses WebGPU SmolVLM; English speech uses Kokoro through WASM, and Polish speech uses Piper through WASM. These models differ from the native versions. SmolVLM is much smaller and may struggle with dense papers; choose OpenRouter explicitly for stronger models. Models download from Hugging Face and are cached by Transformers.js. Failure never switches to a paid provider. Cached files may be evicted by the browser. After a model is downloaded, keep the same browser and origin for offline use.

Speech is exported to AAC/M4A locally using single-thread FFmpeg WASM, including OpenRouter PCM/WAV and MP3 outputs. Shared recordings retain the existing SHA-256 verification and 25 MB limit. No API proxy receives photos or keys; OpenRouter calls go directly from the browser to OpenRouter. The OpenRouter key is tab-session-only unless Remember is checked; remembered keys are in browser storage, which does not provide native Keychain/Keystore isolation.

IndexedDB retains photos, answers, audio, stage, playback positions, incoming queues, consumed IDs and durable outgoing snapshots. Cancellation/interruption leaves an explicit retry, without automatically repeating paid requests. Personal Finish deletes personal photos/answers/audio; shared Finish consumes one listener item and loads the next paused. Back keeps the session and does not reopen listening automatically. Native and web anonymous identities are independent per installation/browser.

Active pages poll every fifteen seconds and sync on foreground/online. Browsers cannot guarantee background queue downloads, web push is not configured, and iPhone may suspend locked-screen audio. Media Session earbud commands and wake lock depend on the browser. No arbitrary left/right earbud gesture interception is possible. These differences are shown in Settings; the web app does not claim native background reliability.

## Browser verification

`tests/harness.html` is a development-only fixture page, excluded from the production Vite build. It generates a 20-second tone, exercises WAV→AAC conversion and can seed a local listening session for gesture and restore checks. It sends no paid AI requests. Real AI model quality, Safari camera/screen-off behavior and physical earbuds need device acceptance testing. The live Supabase preflight/auth smoke checks and native queue contract are verified separately.

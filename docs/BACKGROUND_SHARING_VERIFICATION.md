# Background playback and sharing verification - 9 October 2026

## Implemented

- Media3 MediaSessionService with notification/media controls, five-second earbud seeking, audio focus, noisy-output handling and playback resumption. Activity stop no longer pauses playback.
- Foreground preparation service with Cancel, appropriate network/local-media types and a bounded partial wake lock. Application-scoped jobs survive Activity destruction. Room records interrupted generation; saved answer text can retry speech without another solving request.
- Room session persistence and migration from legacy atomic JSON; separate personal/shared playback positions, consumed-item records and durable owner upload snapshots.
- Code-based sharing controls, ten-listener limit, latest-item joining, independent FIFO progression, paused next-item loading, shared three-second hold, offline received audio and upload/download retries.
- AAC export, private cloud audio, checksum verification, authenticated server operations, immutable published objects, idempotent finalization, join throttling and 24-hour expiry/cleanup.
- FCM hints and WorkManager reconciliation; active home-screen sync checks every fifteen seconds. This implementation uses reconciliation rather than a persistent Realtime websocket.
- Personal and shared completion notifications open the appropriate workspace. Ready sound respects app settings and Android notification settings; arrivals do not interrupt current playback.

## Passed locally

- Debug APK and Android test APK compile; Android lint has no errors.
- 46 JVM tests: existing solving/speech/notation/tap/cancellation tests plus a standard SHA-256 integrity fixture.
- 15 Android instrumentation tests on Pixel 6 / Android 15 / API 35 x86_64. These include actual screen-off playback, continued playback after Activity stop, platform media commands, foreground headset key deduplication, tap/hold gestures, multi-page editing, saved-session restoration, legacy migration, independent queued positions, duplicate advance protection, owner snapshot survival, AAC export and ready sound.
- 11 PostgreSQL tests using PGlite: client-role access denial, idempotent publication, latest-item joining, retained membership cursor, invalid/self/disabled codes, ten-listener cap, rotation, throttling, expiry and disabled publishing.
- Deno type check for the deployed Edge Function source.

Raw successful results are saved alongside this report. No paid AI requests were made as part of these checks. The final packaging pass corrects the sharing panel's punctuation encoding; its behavior matches the tested build.

## Still requires deployment/device verification

The APK has no Supabase/Firebase project configuration and deliberately disables live sharing. Follow SHARING_SETUP.md, deploy the migration/function, configure FCM and hourly cleanup, then rebuild. Hosted Auth, Storage, FCM delivery, Cron and two-phone end-to-end sharing have not been exercised here. SQL tests use the Supabase role/schema shape but do not replace hosted integration tests.

A physical Xiaomi phone and Xiaomi Earbuds 8 Active still need validation. Emulator media-command tests cannot prove the earbuds' firmware mapping or manufacturer background restrictions. Screen-off solving/TTS with a live model/provider was not exercised in this run. Android force-stop and resource restrictions can interrupt jobs; playback restores paused and generation never silently repeats a paid request.

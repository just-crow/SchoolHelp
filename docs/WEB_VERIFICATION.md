# Website verification — 12 October 2026

Published at https://just-crow.github.io/SchoolHelp/ through the repository's GitHub Pages workflow. The production build and initial hosted workflow passed. Source and package lock are in `web/`.

Passed:

- Eleven web tests: continued-page parsing, listening-only notation validation, request photo order and notation rewrite, selected speech model/voice, response-driven PCM wrapping, explicit credit failures without retries, tap/hold timing, queue order/idempotent consumption, and pasted API-header normalization.
- Twelve existing PostgreSQL backend tests; Deno sharing Edge Function type-check.
- Sharing Edge Function version 6 deployment: browser preflight returns 204 with CORS headers; unauthenticated profile access still returns 401.
- Production browser anonymous sign-in/profile creation, join to a temporary test owner, shared waiting, Back, and disconnect. Test owner was disabled afterwards and had zero listeners.
- Browser photo sample import and crop dialog/apply; model catalog loads and filters live OpenRouter vision models.
- Browser WAV→AAC/M4A export of a twenty-second fixture, duration loading while paused, restored eight-second position, double tap seeking to thirteen seconds, triple tap playback, 1.5-second hold restart, three-second hold completion, Back/Home persistence after reload.
- Production English Kokoro, Polish Piper and WebGPU SmolVLM model loading; English and Polish initialization generates a short utterance locally.

No paid OpenRouter generation request was made. The paid-request paths use mocked responses in tests. Real provider generation, local-model answer quality, two physical native/web devices, Safari camera/locked-screen audio, PWA installation and physical earbuds still need device acceptance testing. Browser background synchronization is intentionally described as limited; push delivery is not configured for the website.

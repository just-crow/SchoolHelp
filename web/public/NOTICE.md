# Third-party notices

Lora and Manrope fonts and their SIL Open Font License notices are provided in `licenses/`. SchoolHelp reuses the fonts and ready chime from its Android app.

Browser model/runtime packages are pinned in package-lock.json: Transformers.js and ONNX Runtime (Apache-2.0/MIT), Kokoro.js (Apache-2.0), Piper browser adapter (MIT), FFmpeg.wasm (MIT wrapper; FFmpeg core is GPL-2.0-or-later and contains separately licensed codecs), Supabase JS (MIT), Vite (MIT). Refer to the upstream packages for complete licenses and source:

- https://github.com/huggingface/transformers.js
- https://github.com/microsoft/onnxruntime
- https://github.com/hexgrad/kokoro
- https://github.com/Mintplex-Labs/piper-tts-web
- https://github.com/diffusionstudio/piper-wasm
- https://github.com/ffmpegwasm/ffmpeg.wasm
- https://github.com/ffmpegwasm/ffmpeg.wasm-core
- https://github.com/supabase/supabase-js
- https://github.com/vitejs/vite

Downloaded model weights retain their own model-card licenses. SmolVLM is downloaded from HuggingFaceTB/SmolVLM-256M-Instruct at the source-pinned revision; Kokoro from onnx-community/Kokoro-82M-v1.0-ONNX; the Polish Gosia Piper voice from diffusionstudio/piper-voices. No downloaded weights are committed to this repository.

import {defineConfig} from 'vite';
export default defineConfig({base:'./', worker:{format:'es'}, build:{chunkSizeWarningLimit:1500}, server:{headers:{'Cross-Origin-Opener-Policy':'same-origin','Cross-Origin-Embedder-Policy':'credentialless'}}});

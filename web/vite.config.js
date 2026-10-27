import {defineConfig} from 'vite';
import {writeFileSync,readFileSync} from 'node:fs';
const version=process.env.GITHUB_SHA || String(Date.now());
export default defineConfig({base:'./', define:{__APP_VERSION__:JSON.stringify(version)},
 plugins:[{name:'update-version',closeBundle(){
  writeFileSync('dist/version.json',JSON.stringify({version}));
  writeFileSync('dist/sw.js',readFileSync('dist/sw.js','utf8').replace('schoolhelp-shell-v1',`schoolhelp-shell-${version}`));
 }}], worker:{format:'es'}, build:{chunkSizeWarningLimit:1500}, server:{headers:{'Cross-Origin-Opener-Policy':'same-origin','Cross-Origin-Embedder-Policy':'credentialless'}}});

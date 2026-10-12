const connection=new Promise((resolve,reject)=>{const request=indexedDB.open('schoolhelp',1);request.onupgradeneeded=()=>request.result.createObjectStore('records');request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error)});
async function transaction(mode,fn){const db=await connection;return new Promise((resolve,reject)=>{const tx=db.transaction('records',mode),store=tx.objectStore('records');let result;try{result=fn(store)}catch(error){reject(error);return}tx.oncomplete=()=>resolve(result?.result);tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error)})}
export const get=key=>transaction('readonly',s=>s.get(key));
export const put=(key,value)=>transaction('readwrite',s=>s.put(value,key));
export const del=key=>transaction('readwrite',s=>s.delete(key));
export async function update(key,fn){const db=await connection;return new Promise((resolve,reject)=>{const tx=db.transaction('records','readwrite'),store=tx.objectStore('records'),req=store.get(key);let result;req.onsuccess=()=>{try{result=fn(req.result);store.put(result,key)}catch(error){tx.abort();reject(error)}};tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(tx.error)})}

import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire(new URL('../../.tools/backend-tests/package.json',import.meta.url));
const {PGlite}=require('@electric-sql/pglite');
const db=new PGlite();
await db.exec(`create role anon; create role authenticated; create role service_role;
create schema auth; create table auth.users(id uuid primary key);
create schema storage; create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);`);
const migration=await readFile(new URL('../../supabase/migrations/20261010200807_schoolhelp_sharing.sql',import.meta.url),'utf8');
// PGlite runs actual PostgreSQL; Supabase supplies the pgcrypto extension in production.
await db.exec(migration.replace('create extension if not exists pgcrypto with schema extensions;',''));
await db.exec(await readFile(new URL('../../supabase/migrations/20261010222327_sharing_push_platform.sql',import.meta.url),'utf8'));
const owner='00000000-0000-0000-0000-000000000001';
const other='00000000-0000-0000-0000-000000000002';
const listeners=Array.from({length:12},(_,i)=>`00000000-0000-0000-0000-${String(i+10).padStart(12,'0')}`);
for(const id of [owner,other,...listeners]) await db.query('insert into auth.users(id) values($1)',[id]);
await db.query('insert into sharing_profiles(owner,code,enabled) values($1,$2,true),($3,$4,false)',[owner,'ABCDEFGH23',other,'ZYXWVUTS23']);
let count=0;
async function test(name,fn) { await fn(); console.log('PASS '+name); count++; }
const scalar=async(sql,args=[])=>Object.values((await db.query(sql,args)).rows[0])[0];
async function publish(n) { const id=`10000000-0000-0000-0000-${String(n).padStart(12,'0')}`;
 const args=[owner,id,JSON.stringify({problems:[{answer:'Cell'}]}),'Write cell.','en',1,`${owner}/${id}.m4a`,'checksum',100];
 return scalar('select sharing_publish($1,$2,$3,$4,$5,$6,$7,$8,$9)',args); }
await test('anonymous clients cannot read profiles or enumerate codes',async()=>{
 await db.exec('set role anon'); await assert.rejects(()=>db.query('select * from sharing_profiles'),/permission denied/); await db.exec('reset role');
});
await test('authenticated clients cannot call privileged join',async()=>{
 await db.exec('set role authenticated'); await assert.rejects(()=>db.query('select sharing_join($1,$2)',[listeners[0],'ABCDEFGH23']),/permission denied/); await db.exec('reset role');
});
await test('publication retries keep the same sequence',async()=>{ assert.equal(await publish(1),await publish(1)); assert.equal(await scalar('select count(*) from sharing_items'),1); });
await test('late join starts at latest item and includes future publications',async()=>{
 const second=await publish(2); await db.query('select sharing_join($1,$2)',[listeners[0],'ABCDEFGH23']);
 assert.equal(await scalar('select start_sequence from sharing_memberships where listener=$1',[listeners[0]]),second);
 await publish(3); assert.equal(await scalar('select count(*) from sharing_items where sequence >= (select start_sequence from sharing_memberships where listener=$1)',[listeners[0]]),2);
});
await test('repeated join keeps original cursor',async()=>{
 const before=await scalar('select start_sequence from sharing_memberships where listener=$1',[listeners[0]]);
 await db.query('select sharing_join($1,$2)',[listeners[0],'ABCDEFGH23']);
 assert.equal(await scalar('select start_sequence from sharing_memberships where listener=$1',[listeners[0]]),before);
});
await test('invalid code, own code, and disabled owner are rejected',async()=>{
 await assert.rejects(()=>db.query('select sharing_join($1,$2)',[listeners[1],'XXXXXXXXXX']),/Code unavailable/);
 await assert.rejects(()=>db.query('select sharing_join($1,$2)',[owner,'ABCDEFGH23']),/own code/);
 await assert.rejects(()=>db.query('select sharing_join($1,$2)',[listeners[1],'ZYXWVUTS23']),/sharing disabled/);
});
await test('ten listener limit is enforced',async()=>{
 for(const id of listeners.slice(1,10)) await db.query('select sharing_join($1,$2)',[id,'ABCDEFGH23']);
 await assert.rejects(()=>db.query('select sharing_join($1,$2)',[listeners[10],'ABCDEFGH23']),/ten listeners/);
});
await test('rotation rejects old code and preserves existing membership',async()=>{
 await db.query('update sharing_profiles set code=$1 where owner=$2',['NEWCODE234',owner]);
 await assert.rejects(()=>db.query('select sharing_join($1,$2)',[listeners[10],'ABCDEFGH23']),/Code unavailable/);
 assert.equal(await scalar('select count(*) from sharing_memberships where owner=$1',[owner]),10);
});
await test('join attempts are throttled after twenty per hour',async()=>{
 for(let i=0;i<20;i++) assert.equal(await scalar('select sharing_allow_join($1)',[listeners[11]]),true);
 assert.equal(await scalar('select sharing_allow_join($1)',[listeners[11]]),false);
});
await test('expired items are excluded from fresh joins',async()=>{
 await db.query("update sharing_items set expires_at=now()-interval '1 second'");
 await db.query('delete from sharing_memberships where listener=$1',[listeners[1]]);
 await db.query('select sharing_join($1,$2)',[listeners[1],'NEWCODE234']);
 assert.equal(await scalar('select start_sequence from sharing_memberships where listener=$1',[listeners[1]]),0);
 assert.equal(await scalar('select count(*) from sharing_items where expires_at>now()'),0);
});
await test('disabled sharing blocks publishing without deleting earlier items',async()=>{
 await db.query('update sharing_profiles set enabled=false where owner=$1',[owner]);
 await assert.rejects(()=>publish(4),/Sharing is disabled/);
 assert.equal(await scalar('select count(*) from sharing_items'),3);
});
await test('Android push tokens remain default and iOS tokens are accepted',async()=>{
 await db.query('insert into sharing_tokens(listener,token) values($1,$2)',[listeners[0],'android-token']);
 await db.query('insert into sharing_tokens(listener,token,platform) values($1,$2,$3)',[listeners[1],'ios-token','ios']);
 assert.equal(await scalar('select count(*) from sharing_tokens where platform=\'android\''),1);
 assert.equal(await scalar('select count(*) from sharing_tokens where platform=\'ios\''),1);
});
console.log(`OK (${count} backend tests)`);
await db.close();

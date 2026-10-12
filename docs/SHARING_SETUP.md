# SchoolHelp sharing setup

Personal playback works without a server. Live code sharing needs your own Supabase Free and Firebase Spark projects. This implementation does not enable billing. Listeners receive text and the owner's AAC recording, never photos or API keys.

## Current deployment (10 October 2026)

The SchoolHelp project `keexrxwjcnyeetzxmehr` is deployed through the MakEduc connection. Anonymous sign-ins, the sharing database, private audio bucket, sharing Edge Function and hourly expiry cleanup are configured and live-tested. Use `android/SchoolHelp-firebase-configured.apk` for Android. Firebase FCM sending is configured, although physical-device notification delivery still needs testing; both apps reconcile their queues on return to the foreground.

Local migration filenames match hosted migration history. The cleanup migration is specific to this project's function URL; adjust it before deploying a different project. It creates a random Vault cleanup credential and schedules the hourly job automatically. The manual cleanup setup below is only an alternative for deployments that do not use that migration.

## Supabase

1. Create a Supabase Free project and enable anonymous sign-ins under Authentication / Providers. Keep signup rate limiting enabled.
2. From this folder, use the Supabase CLI to apply the migration and deploy the function:

```powershell
npx supabase login
npx supabase link --project-ref YOUR_PROJECT_REF
npx supabase db push
npx supabase functions deploy sharing --no-verify-jwt
```

The function validates the access token using Supabase Auth. Its gateway JWT check is disabled because authentication is performed inside the function. Tables deny direct access to anonymous/authenticated database roles. Audio is private; signed download URLs are issued only after membership checks. Never put the service-role key in the APK.

3. Add the public Android client configuration to the existing ignored `android/local.properties`:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_ANON_KEY=YOUR_PUBLIC_ANON_KEY
```

## Firebase Cloud Messaging

Create a Firebase project on Spark, register Android package `com.schoolhelp.app`, and enable the FCM HTTP v1 API. Firebase Storage and Firebase Functions are not used. Copy these public values from its Android configuration to `android/local.properties`:

```properties
FIREBASE_APP_ID=1:YOUR_SENDER_ID:android:YOUR_APP_ID
FIREBASE_API_KEY=YOUR_PUBLIC_ANDROID_API_KEY
FIREBASE_PROJECT_ID=YOUR_FIREBASE_PROJECT_ID
FIREBASE_SENDER_ID=YOUR_PROJECT_NUMBER
```

Create a service account permitted to send FCM messages. Keep its JSON outside the repository. In the Supabase secrets dashboard, set `FCM_SERVICE_ACCOUNT` to that JSON. This secret stays on the server. Never include it in local.properties, the APK, or source control.

Allow notifications on phones. Push contains only a generic synchronization hint. WorkManager downloads the recording and verifies its checksum. Devices without Google Play services catch up on reopening and scheduled network work.

## Hourly cleanup

Set a random `CLEANUP_SECRET` in Supabase function secrets. Enable Supabase Cron, pg_net, and Vault. In Vault, store the same secret as `schoolhelp_cleanup_secret` and the function URL (`https://YOUR_PROJECT_REF.supabase.co/functions/v1/sharing`) as `schoolhelp_sharing_url`. Schedule cleanup using the SQL editor:

```sql
select cron.schedule('schoolhelp-cleanup', '0 * * * *', $$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name='schoolhelp_sharing_url'),
    headers := jsonb_build_object('Content-Type','application/json','x-cleanup-secret',
      (select decrypted_secret from vault.decrypted_secrets where name='schoolhelp_cleanup_secret')),
    body := '{"action":"cleanup"}'::jsonb
  );
$$);
```

Online items stop appearing in queues after 24 hours; hourly cleanup removes their audio and metadata. It also removes abandoned uploads older than three hours. Signed download URLs last five minutes. Downloaded recordings stay on listeners until consumed/disconnected. Check Cron history and function responses: failed cleanup can exhaust free storage.

## Build and verification

Rebuild after adding project configuration using the existing Gradle/JDK scripts. Enable Shared listening on the owner, and enter its code on another phone.

Local SQL tests run real PostgreSQL through PGlite, without a hosted account:

```powershell
npm install --prefix .tools/backend-tests --no-audit --no-fund @electric-sql/pglite
node scripts/backend-tests/sharing.mjs
npx --yes deno check supabase/functions/sharing/index.ts
```

They validate SQL behavior and privileges; they do not exercise deployed Auth, Storage, FCM, or Cron. Before distributing a configured APK, use two phones: publish an answer, receive it, exit owner listening mode, publish two more, and advance the listener independently. Repeat with two listeners, late joining, offline/reconnect, duplicate push, removal, rotation and expiration.

## Behavior and limits

- Anonymous identity and a ten-character code belong to one installation; clearing app data/reinstalling loses them. There is no recovery account.
- Each phone follows one owner. Each owner accepts ten listeners. Late joins receive the latest unexpired item and future publications.
- Sharing off blocks new joins/publications. Rotation blocks the old code for future joins. Existing memberships and downloaded files remain until removed/disconnected.
- Shared Next/three-second hold consumes one answer and loads the next paused. Earbud next/previous seek five seconds. Back preserves the item. Personal three-second hold clears personal photos/text/audio only.
- Each phone keeps independent position and consumption records. Incoming audio is checksum-verified and saved atomically. Owner publication snapshots survive clearing personal work.
- Playback and started solving/TTS use foreground services. Normal screen lock is supported; force-stop and Android/OEM restrictions can interrupt work. Interrupted speech can be retried from saved answers without repeating the AI call.
- Foreground home synchronization checks every fifteen seconds. Background delivery uses FCM and fifteen-minute periodic WorkManager recovery. Doze can delay delivery.
- Audio uploads are capped at 25 MB. A server guard reserves room below the free storage allowance. Monitor storage, bandwidth and function quotas; free-tier failures preserve personal use and show retry status, with no automatic upgrade.
- Room keeps small session/queue records; legacy JSON remains as a recovery copy after migration. AI calls, conversion and file transfer run off the UI thread.
# iPhone compatibility (11 October 2026)

The native iPhone source is under `ios/`; Android is under `android/`. Both use this deployed Supabase project and the same code, queue, solution JSON, and AAC/M4A audio format. The migration `20261010222327_sharing_push_platform.sql` adds an optional push platform; older Android clients default to `android`. The sharing Edge Function is deployed at version 5 with iOS FCM/APNs payload support. Apple signing and the APNs key upload remain necessary before iPhone push can work. See `ios/README.md` for Mac build instructions. Push gives a generic arrival alert and sync hint; queued content is always recovered by foreground sync.


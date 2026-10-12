# Supabase deployment - 10 October 2026

Project: SchoolHelp (`keexrxwjcnyeetzxmehr`), MakEduc account connection.

Deployed migrations:
- 20261010200807_schoolhelp_sharing.sql
- 20261010201046_sharing_cleanup.sql
- 20261010222327_sharing_push_platform.sql

The sharing Edge Function is active (version 5), with explicit user-token authentication inside the function. Gateway JWT verification is disabled to permit that authentication and the separately authenticated cleanup path. No privileged credentials are included in either mobile client.

Live checks passed: anonymous Auth signup; unauthenticated access denial; signed audio-object upload; checksum verification; idempotent publication; joining by code; signed private-file download; ordered future publications while preserving the current item; continued membership access after sharing is switched off; removal revoking queue access; and rejection of public code enumeration. File-transfer tests used non-playable integrity fixtures, not generated answer audio; AAC generation/playback was tested separately on Android previously.

Hourly cleanup (`0 * * * *`) is active, authenticated with a random secret stored in Supabase Vault. A manual invocation returned HTTP 200 and removed both expired test items. Temporary test accounts were subsequently deleted. No user content was changed.

Performance advisors: no findings. Security advisors: informational RLS-without-policy findings are intentional because all table access goes through the authenticated Edge Function using the server role; client grants are revoked. The project's password-leak protection warning does not apply to this anonymous-only flow. Advisor references:
- https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy
- https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection

Physical two-phone/earbud acceptance tests remain pending. Background queues reconcile through WorkManager; foreground home screens poll every fifteen seconds.

Firebase Android registration completed: project schoolhelp-f47ed, package com.schoolhelp.app. Public Firebase configuration is in ignored local.properties. SchoolHelp-firebase-configured.apk builds successfully. The dedicated schoolhelp-push service account has the Firebase Cloud Messaging API Admin role. Its credential is installed as the Supabase Edge Function secret FCM_SERVICE_ACCOUNT. Credentials are excluded from source control and the APK. Physical device push delivery has not been tested.

FCM server verification passed: service-account OAuth authentication and HTTP v1 validate-only send with the production data payload. No device notification was sent. Google IAM propagation caused initial permission errors, then the same credential passed. No Android device was connected for delivery testing.

11 October 2026: deployed sharing Edge Function version 5 and migration `20261010222327_sharing_push_platform.sql`. Push tokens now store `platform` with default `android`; the iOS client submits `ios`. iPhone pushes carry a generic visible APNs alert and sync hint, with no answer content. The iOS Firebase app is registered in `schoolhelp-f47ed`. APNs credentials and physical iPhone delivery remain pending.

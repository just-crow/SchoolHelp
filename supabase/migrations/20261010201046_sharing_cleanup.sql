create extension if not exists pg_cron with schema pg_catalog;
create extension if not exists pg_net with schema extensions;
do $$
begin
  if not exists(select 1 from vault.secrets where name='schoolhelp_cleanup_secret') then
    perform vault.create_secret(encode(extensions.gen_random_bytes(32),'hex'),'schoolhelp_cleanup_secret','SchoolHelp hourly cleanup authentication');
  end if;
end $$;
create or replace function public.sharing_cleanup_authorized(p_token text)
returns boolean language sql security definer set search_path='' as $$
  select coalesce(length(p_token)>=32 and p_token=(select decrypted_secret from vault.decrypted_secrets where name='schoolhelp_cleanup_secret' limit 1),false)
$$;
revoke all on function public.sharing_cleanup_authorized(text) from public,anon,authenticated;
grant execute on function public.sharing_cleanup_authorized(text) to service_role;
select cron.schedule('schoolhelp-cleanup','0 * * * *',$job$
  select net.http_post(
    url:='https://keexrxwjcnyeetzxmehr.supabase.co/functions/v1/sharing',
    headers:=jsonb_build_object('Content-Type','application/json','x-cleanup-secret',(select decrypted_secret from vault.decrypted_secrets where name='schoolhelp_cleanup_secret')),
    body:='{"action":"cleanup"}'::jsonb,
    timeout_milliseconds:=60000
  );
$job$);

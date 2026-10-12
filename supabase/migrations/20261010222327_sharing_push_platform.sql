alter table public.sharing_tokens add column if not exists platform text not null default 'android'
  check (platform in ('android', 'ios'));

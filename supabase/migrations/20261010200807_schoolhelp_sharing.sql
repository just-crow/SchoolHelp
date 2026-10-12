-- All data access goes through the authenticated sharing Edge Function.
create extension if not exists pgcrypto with schema extensions;
create table public.sharing_profiles (
  owner uuid primary key references auth.users(id) on delete cascade,
  code text unique not null,
  enabled boolean not null default false
);
create table public.sharing_memberships (
  owner uuid not null references public.sharing_profiles(owner) on delete cascade,
  listener uuid not null references auth.users(id) on delete cascade,
  start_sequence bigint not null,
  joined_at timestamptz not null default now(),
  primary key(owner,listener), unique(listener), check(owner<>listener)
);
create table public.sharing_items (
  id uuid primary key,
  owner uuid not null references public.sharing_profiles(owner) on delete cascade,
  sequence bigint generated always as identity unique,
  solution jsonb not null,
  spoken_text text not null,
  language text not null,
  speed real not null check(speed > 0 and speed <= 4),
  object_path text not null unique,
  checksum text not null,
  bytes bigint not null check(bytes between 1 and 25000000),
  published_at timestamptz not null default now(),
  expires_at timestamptz not null default now()+interval '24 hours'
);
create index sharing_items_owner_order on public.sharing_items(owner,sequence);
create table public.sharing_tokens (listener uuid primary key references auth.users(id) on delete cascade, token text not null);
create table public.sharing_join_limits (listener uuid primary key references auth.users(id) on delete cascade, window_start timestamptz not null default now(), attempts integer not null default 0);
alter table public.sharing_profiles enable row level security;
alter table public.sharing_memberships enable row level security;
alter table public.sharing_items enable row level security;
alter table public.sharing_tokens enable row level security;
alter table public.sharing_join_limits enable row level security;
-- No anonymous/authenticated direct policies: only the server role has access.
revoke all on public.sharing_profiles,public.sharing_memberships,public.sharing_items,public.sharing_tokens,public.sharing_join_limits from anon,authenticated;
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('shared-audio','shared-audio',false,25000000,array['audio/mp4']) on conflict(id) do nothing;

create or replace function public.sharing_join(p_listener uuid,p_code text) returns uuid
language plpgsql security definer set search_path=public as $$
declare p public.sharing_profiles; start_at bigint; existing uuid;
begin
  select * into p from public.sharing_profiles where code=p_code for update;
  if not found or not p.enabled then raise exception 'Code unavailable or sharing disabled'; end if;
  if p.owner=p_listener then raise exception 'You cannot connect to your own code'; end if;
  select owner into existing from public.sharing_memberships where listener=p_listener;
  if existing=p.owner then return p.owner; end if;
  if existing is not null then raise exception 'Disconnect from the current owner first'; end if;
  if (select count(*) from public.sharing_memberships where owner=p.owner)>=10 then raise exception 'This owner already has ten listeners'; end if;
  select coalesce(max(sequence),0) into start_at from public.sharing_items where owner=p.owner and expires_at>now();
  insert into public.sharing_memberships(owner,listener,start_sequence) values(p.owner,p_listener,start_at);
  return p.owner;
end $$;
create or replace function public.sharing_publish(p_owner uuid,p_id uuid,p_solution jsonb,p_spoken text,p_language text,p_speed real,p_path text,p_checksum text,p_bytes bigint)
returns bigint language plpgsql security definer set search_path=public as $$
declare seq bigint; enabled_now boolean;
begin
  select enabled into enabled_now from public.sharing_profiles where owner=p_owner for update;
  select sequence into seq from public.sharing_items where id=p_id and owner=p_owner;
  if found then return seq; end if;
  if not coalesce(enabled_now,false) then raise exception 'Sharing is disabled; re-enable to publish pending answers'; end if;
  insert into public.sharing_items(id,owner,solution,spoken_text,language,speed,object_path,checksum,bytes)
    values(p_id,p_owner,p_solution,p_spoken,p_language,p_speed,p_path,p_checksum,p_bytes) returning sequence into seq;
  return seq;
end $$;
-- Attempts are recorded before joining, including failed code lookups.
create or replace function public.sharing_allow_join(p_listener uuid) returns boolean
language plpgsql security definer set search_path=public as $$
declare n integer;
begin
  insert into public.sharing_join_limits(listener,attempts) values(p_listener,1)
  on conflict(listener) do update set
    attempts=case when sharing_join_limits.window_start<now()-interval '1 hour' then 1 else sharing_join_limits.attempts+1 end,
    window_start=case when sharing_join_limits.window_start<now()-interval '1 hour' then now() else sharing_join_limits.window_start end
  returning attempts into n;
  return n<=20;
end $$;
revoke all on function public.sharing_join(uuid,text),public.sharing_allow_join(uuid),public.sharing_publish(uuid,uuid,jsonb,text,text,real,text,text,bigint) from public,anon,authenticated;
grant execute on function public.sharing_join(uuid,text),public.sharing_allow_join(uuid),public.sharing_publish(uuid,uuid,jsonb,text,text,real,text,text,bigint) to service_role;

create or replace function public.sharing_total_bytes() returns bigint
language sql security definer set search_path=public as $$ select coalesce(sum(bytes),0)::bigint from public.sharing_items $$;
revoke all on function public.sharing_total_bytes() from public,anon,authenticated;
grant execute on function public.sharing_total_bytes() to service_role;

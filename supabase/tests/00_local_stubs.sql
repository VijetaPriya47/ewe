-- Local test stand-ins for what Supabase provides (auth schema, roles, pgvector).
-- NEVER run this against a real Supabase project.
create schema if not exists auth;
create table if not exists auth.users (id uuid primary key);
create or replace function auth.uid() returns uuid language sql stable as
$$ select nullif(coalesce(current_setting('request.jwt.claims', true), '{}')::jsonb ->> 'sub', '')::uuid $$;
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticator') then
    create role authenticator login password 'test' noinherit; end if;
end $$;
grant authenticated, anon to authenticator;
grant usage on schema auth to authenticated, anon;
create schema if not exists extensions;
grant usage on schema extensions to authenticated, anon;
-- pgvector stand-in (only when the real extension is not installed): float8[] + cosine distance
do $$ begin
  if not exists (select 1 from pg_available_extensions where name = 'vector') then
    create domain extensions.vector as double precision[];
    create function extensions.cosine_distance(a double precision[], b double precision[])
    returns double precision language sql immutable as $f$
      select 1 - sum(x * y) / nullif(sqrt(sum(x * x)) * sqrt(sum(y * y)), 0)
      from unnest(a, b) as t(x, y) $f$;
    create operator extensions.<=> (leftarg = double precision[], rightarg = double precision[],
                                    function = extensions.cosine_distance);
  end if;
end $$;

-- Supabase Storage stand-in: just enough for the "figures" bucket and its policies.
create schema if not exists storage;
create table if not exists storage.buckets (
  id text primary key, name text, public boolean default false, file_size_limit bigint, allowed_mime_types text[]);
create table if not exists storage.objects (
  id uuid primary key default gen_random_uuid(), bucket_id text references storage.buckets(id), name text, owner uuid default auth.uid());
create or replace function storage.foldername(name text) returns text[] language sql immutable as
$$ select (string_to_array(name, '/'))[1:array_length(string_to_array(name, '/'), 1) - 1] $$;
alter table storage.objects enable row level security;
grant usage on schema storage to authenticated;
grant select, insert, update, delete on storage.objects to authenticated;
grant select on storage.buckets to authenticated;

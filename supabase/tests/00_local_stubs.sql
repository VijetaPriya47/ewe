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

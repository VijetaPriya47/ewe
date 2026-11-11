-- ewe — database schema (Supabase / Postgres + pgvector)
--
-- Rules enforced here, not only in the app:
--   * Session type (topic | project) is chosen at creation and can never change.
--   * Sessions can never be deleted — only marked active / inactive.
--   * Chat messages are read-only: no edits, no deletes.
--   * Notes (sections + bullets) can be edited and deleted freely, no history kept.
--   * Every bullet records the date it was added and the session it came from.
--   * A linked point is one row shown in two places (bullet_links), never a copy.
--   * The embedding model can only be changed while Linking is on, and the model the
--     index was built with (rag_active_model) only changes when a rebuild completes.

create schema if not exists extensions;
create extension if not exists vector with schema extensions;

-- ───────────────────────── settings (one row per user) ─────────────────────────
create table public.settings (
  user_id                uuid primary key default auth.uid() references auth.users(id),
  -- per-task provider + model (keys never leave the phone)
  notes_provider         text,
  notes_model            text,
  placement_provider     text,
  placement_model        text,
  podcast_provider       text,
  podcast_model          text,
  -- linking (RAG). Models are stored as "provider:model".
  rag_enabled            boolean not null default false,
  rag_selected_model     text,   -- what the UI shows
  rag_active_model       text,   -- what the stored embeddings were built with
  -- daily podcast
  podcast_enabled        boolean not null default false,
  interval_cap_days      int  not null default 64 check (interval_cap_days between 1 and 1024),
  daily_job_time         time not null default '01:00',
  podcast_target_minutes int  not null default 30 check (podcast_target_minutes between 5 and 180),
  revisions_per_level    int  not null default 2  check (revisions_per_level between 1 and 10),
  -- storage
  keep_photo_copies      boolean not null default false,
  updated_at             timestamptz not null default now()
);

create function public.settings_guard() returns trigger
language plpgsql as $$
begin
  if current_setting('ewe.rag_internal', true) is distinct from '1' then
    if new.rag_selected_model is distinct from old.rag_selected_model and not new.rag_enabled then
      raise exception 'The embedding model can only be changed while Linking is on.';
    end if;
    if new.rag_active_model is distinct from old.rag_active_model then
      raise exception 'rag_active_model changes only when a rebuild finishes.';
    end if;
  end if;
  new.updated_at := now();
  return new;
end $$;
create trigger settings_guard_upd before update on public.settings
  for each row execute function public.settings_guard();

-- ───────────────────────── sessions ─────────────────────────
create table public.sessions (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid() references auth.users(id),
  title           text not null check (length(trim(title)) > 0),
  type            text not null check (type in ('topic', 'project')),
  is_active       boolean not null default true,
  recreated_from  uuid references public.sessions(id),   -- set by "Recreate as Topic/Project"
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  -- Linking: the "parent row", embedded from a short summary of the session
  embedding               extensions.vector,
  embedding_model         text,
  embedding_hash          text,
  embedding_staging       extensions.vector,
  embedding_staging_model text,
  embedding_staging_hash  text
);
create index sessions_user_idx on public.sessions (user_id, is_active, updated_at desc);

create function public.sessions_guard() returns trigger
language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'Sessions cannot be deleted — mark them inactive instead.';
  end if;
  if new.type <> old.type then
    raise exception 'Session type cannot be changed after creation. Use "Recreate as %" instead.',
      case when old.type = 'topic' then 'Project' else 'Topic' end;
  end if;
  if new.user_id <> old.user_id or new.created_at <> old.created_at then
    raise exception 'user_id / created_at are immutable.';
  end if;
  if new.title is distinct from old.title or new.is_active is distinct from old.is_active then
    new.updated_at := now();
  end if;
  return new;
end $$;
create trigger sessions_guard_upd before update on public.sessions
  for each row execute function public.sessions_guard();
create trigger sessions_guard_del before delete on public.sessions
  for each row execute function public.sessions_guard();

-- ───────────────────────── chat messages (read-only log) ─────────────────────────
-- Photos are never stored: only the text the user typed and how many images were sent.
create table public.messages (
  id            uuid primary key default gen_random_uuid(),
  session_id    uuid not null references public.sessions(id),
  user_id       uuid not null default auth.uid() references auth.users(id),
  role          text not null check (role in ('user', 'assistant')),
  content       text not null default '',
  image_count   int  not null default 0,
  created_at    timestamptz not null default now()
);
create index messages_session_idx on public.messages (session_id, created_at);

create function public.messages_readonly() returns trigger
language plpgsql as $$
begin
  raise exception 'Chat messages are read-only — edit the Notes tab instead.';
end $$;
create trigger messages_no_update before update on public.messages
  for each row execute function public.messages_readonly();
create trigger messages_no_delete before delete on public.messages
  for each row execute function public.messages_readonly();

-- ───────────────────────── notes: sections + bullets ─────────────────────────
create table public.sections (
  id          uuid primary key default gen_random_uuid(),
  session_id  uuid not null references public.sessions(id),
  user_id     uuid not null default auth.uid() references auth.users(id),
  title       text not null,
  position    double precision not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  -- Linking: the "child row"
  embedding               extensions.vector,
  embedding_model         text,
  embedding_hash          text,
  embedding_staging       extensions.vector,
  embedding_staging_model text,
  embedding_staging_hash  text
);
create index sections_session_idx on public.sections (session_id, position);

create table public.bullets (
  id                 uuid primary key default gen_random_uuid(),
  section_id         uuid not null references public.sections(id) on delete cascade,
  user_id            uuid not null default auth.uid() references auth.users(id),
  content            text not null check (length(trim(content)) > 0),
  position           double precision not null,
  date_added         date not null default current_date,
  origin_session_id  uuid not null references public.sessions(id),
  origin_message_id  uuid references public.messages(id),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create index bullets_section_idx on public.bullets (section_id, position);
create index bullets_message_idx on public.bullets (origin_message_id);
create index bullets_content_search on public.bullets using gin (to_tsvector('simple', content));
create index sections_title_search  on public.sections using gin (to_tsvector('simple', title));

-- A linked point: the same bullet row, also shown inside a Topic section.
create table public.bullet_links (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id),
  bullet_id   uuid not null references public.bullets(id) on delete cascade,
  section_id  uuid not null references public.sections(id) on delete cascade,
  position    double precision not null,
  created_at  timestamptz not null default now(),
  unique (bullet_id, section_id)
);
create index bullet_links_section_idx on public.bullet_links (section_id, position);

create function public.touch_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;
create trigger bullets_touch before update on public.bullets
  for each row execute function public.touch_updated_at();

-- A section with nothing left in it is removed.
create function public.sections_prune() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  delete from sections s
  where s.id = old.section_id
    and not exists (select 1 from bullets b where b.section_id = s.id)
    and not exists (select 1 from bullet_links l where l.section_id = s.id);
  return null;
end $$;
create trigger bullets_prune after delete on public.bullets
  for each row execute function public.sections_prune();
create trigger bullet_links_prune after delete on public.bullet_links
  for each row execute function public.sections_prune();

-- ───────────────────────── revision schedule (Phase 3) ─────────────────────────
-- One row per batch: a section plus the day its notes were added. New additions to
-- an old topic start their own Day 1.
create table public.revision_schedule (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid() references auth.users(id),
  section_id      uuid not null references public.sections(id) on delete cascade,
  added_date      date not null,
  interval_days   int  not null default 1,
  revisions_done  int  not null default 0,
  next_due_date   date not null,
  last_revised    date,
  unique (section_id, added_date)
);
create index revision_due_idx on public.revision_schedule (user_id, next_due_date);

create table public.revision_log (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null default auth.uid() references auth.users(id),
  schedule_id    uuid not null references public.revision_schedule(id) on delete cascade,
  revised_on     date not null,
  interval_days  int  not null,
  revision_no    int  not null
);

create function public.bullets_schedule() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into revision_schedule (user_id, section_id, added_date, interval_days, next_due_date)
  values (new.user_id, new.section_id, new.date_added, 1, new.date_added + 1)
  on conflict (section_id, added_date) do nothing;
  return new;
end $$;
create trigger bullets_schedule_ins after insert on public.bullets
  for each row execute function public.bullets_schedule();

-- "Mark as listened": the interval doubles (up to the cap) and the next due date is
-- counted from today. Safe to call twice on the same day.
create function public.mark_listened(p_schedule_ids uuid[], p_today date)
returns int
language plpgsql security invoker set search_path = public as $$
declare
  v_cap int;
  v_n   int;
begin
  select coalesce((select interval_cap_days from settings where user_id = auth.uid()), 64) into v_cap;
  with upd as (
    update revision_schedule r
       set revisions_done = r.revisions_done + 1,
           last_revised   = p_today,
           interval_days  = least(r.interval_days * 2, v_cap),
           next_due_date  = p_today + least(r.interval_days * 2, v_cap)
     where r.id = any(p_schedule_ids)
       and r.last_revised is distinct from p_today
    returning r.id, r.revisions_done, r.interval_days
  ), logged as (
    insert into revision_log (schedule_id, revised_on, interval_days, revision_no)
    select id, p_today, interval_days, revisions_done from upd
    returning 1
  )
  select count(*) into v_n from logged;
  return v_n;
end $$;

-- "Restart from Day 1": every section of the session goes back to Day 1, Detailed.
create function public.restart_revision(p_session_id uuid, p_today date)
returns int
language plpgsql security invoker set search_path = public as $$
declare v_n int;
begin
  update revision_schedule r
     set interval_days = 1, revisions_done = 0, next_due_date = p_today, last_revised = null
   where r.section_id in (select id from sections where session_id = p_session_id);
  get diagnostics v_n = row_count;
  return v_n;
end $$;

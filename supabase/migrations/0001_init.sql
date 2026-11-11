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

-- ───────────────────────── apply AI output atomically ─────────────────────────
-- p_payload (built by the edge function after mapping short refs to ids):
-- { "sections": [ { "section_id": uuid|null, "title": text,
--                   "bullets": [ { "text": text, "after_bullet_id": uuid|null } ] } ] }
-- section_id null      → create a new section at the end.
-- after_bullet_id null → append at the end of the section.
-- Returns { new_bullets, new_sections, sections: [{section_id,title,is_new,added,inline}] }.
create function public.apply_notes(p_session_id uuid, p_message_id uuid, p_payload jsonb, p_today date)
returns jsonb
language plpgsql security invoker set search_path = public as $$
declare
  v_type      text;
  v_sec       jsonb;
  v_bul       jsonb;
  v_sec_id    uuid;
  v_is_new    boolean;
  v_title     text;
  v_prev_pos  double precision;
  v_next_pos  double precision;
  v_pos       double precision;
  v_after     uuid;
  v_explicit  uuid;
  v_last_anchor   uuid;
  v_last_inserted uuid;
  v_added     int;
  v_inline    int;
  v_new_secs  int := 0;
  v_new_buls  int := 0;
  v_new_id    uuid;
  v_detail    jsonb := '[]'::jsonb;
begin
  select type into v_type from sessions where id = p_session_id;
  if v_type is null then raise exception 'Session not found'; end if;

  for v_sec in select * from jsonb_array_elements(coalesce(p_payload -> 'sections', '[]'::jsonb)) loop
    v_sec_id := nullif(v_sec ->> 'section_id', '')::uuid;

    -- Projects never get placement choices: always a new section at the end.
    if v_type = 'project' then v_sec_id := null; end if;

    if v_sec_id is not null and not exists
       (select 1 from sections where id = v_sec_id and session_id = p_session_id) then
      v_sec_id := null;  -- unknown/foreign section → fall back to a new section
    end if;

    -- skip groups with no usable bullets (so no empty sections are created)
    if not exists (select 1 from jsonb_array_elements(coalesce(v_sec -> 'bullets', '[]'::jsonb)) b
                   where length(trim(coalesce(b ->> 'text', ''))) > 0) then
      continue;
    end if;

    v_is_new := v_sec_id is null;
    if v_is_new then
      v_title := coalesce(nullif(trim(v_sec ->> 'title'), ''), 'Notes');
      insert into sections (session_id, title, position)
      values (p_session_id, v_title,
              coalesce((select max(position) from sections where session_id = p_session_id), 0) + 1)
      returning id into v_sec_id;
      v_new_secs := v_new_secs + 1;
    else
      update sections set updated_at = now() where id = v_sec_id returning title into v_title;
    end if;

    v_after := null; v_last_anchor := null; v_last_inserted := null; v_added := 0; v_inline := 0;
    for v_bul in select * from jsonb_array_elements(v_sec -> 'bullets') loop
      if length(trim(coalesce(v_bul ->> 'text', ''))) = 0 then continue; end if;

      -- An explicit anchor places the bullet right after that line; several bullets
      -- with the same anchor keep their order. No anchor = append at the end.
      v_explicit := nullif(v_bul ->> 'after_bullet_id', '')::uuid;
      if v_explicit is not null and exists
         (select 1 from bullets where id = v_explicit and section_id = v_sec_id) then
        if v_explicit is not distinct from v_last_anchor then
          v_after := v_last_inserted;
        else
          v_after := v_explicit;
        end if;
        v_last_anchor := v_explicit;
      else
        v_after := null;
        v_last_anchor := null;
      end if;

      if v_after is null then
        v_pos := coalesce((select max(position) from bullets where section_id = v_sec_id), 0) + 1;
      else
        select position into v_prev_pos from bullets where id = v_after;
        select min(position) into v_next_pos from bullets
          where section_id = v_sec_id and position > v_prev_pos;
        if v_next_pos is null then
          v_pos := v_prev_pos + 1;
        else
          v_pos := (v_prev_pos + v_next_pos) / 2;
          v_inline := v_inline + 1;
        end if;
      end if;

      insert into bullets (section_id, content, position, date_added, origin_session_id, origin_message_id)
      values (v_sec_id, trim(v_bul ->> 'text'), v_pos, p_today, p_session_id, p_message_id)
      returning id into v_new_id;
      v_new_buls := v_new_buls + 1;
      v_added := v_added + 1;

      v_last_inserted := v_new_id;
    end loop;

    v_detail := v_detail || jsonb_build_object(
      'section_id', v_sec_id, 'title', v_title, 'is_new', v_is_new, 'added', v_added, 'inline', v_inline);
  end loop;

  update sessions set updated_at = now() where id = p_session_id;
  return jsonb_build_object('new_sections', v_new_secs, 'new_bullets', v_new_buls, 'sections', v_detail);
end $$;

-- ───────────────────────── "Recreate as Topic/Project" ─────────────────────────
-- Copies notes (keeping original dates and revision progress) into a new session of
-- the other type and marks the old one inactive. Chat history stays with the old one.
create function public.recreate_session(p_session_id uuid)
returns uuid
language plpgsql security invoker set search_path = public as $$
declare
  v_old     sessions%rowtype;
  v_new_id  uuid;
  v_sec     record;
  v_new_sec uuid;
begin
  select * into v_old from sessions where id = p_session_id;
  if not found then raise exception 'Session not found'; end if;

  insert into sessions (title, type, recreated_from)
  values (v_old.title, case when v_old.type = 'topic' then 'project' else 'topic' end, v_old.id)
  returning id into v_new_id;

  for v_sec in select * from sections where session_id = v_old.id order by position loop
    if not exists (select 1 from bullets where section_id = v_sec.id) then continue; end if;

    insert into sections (session_id, title, position)
    values (v_new_id, v_sec.title, v_sec.position) returning id into v_new_sec;

    insert into bullets (section_id, content, position, date_added, origin_session_id, origin_message_id)
    select v_new_sec, b.content, b.position, b.date_added,
           case when b.origin_session_id = v_old.id then v_new_id else b.origin_session_id end,
           b.origin_message_id
    from bullets b where b.section_id = v_sec.id;

    update revision_schedule n
       set interval_days = o.interval_days, revisions_done = o.revisions_done,
           next_due_date = o.next_due_date, last_revised = o.last_revised
      from revision_schedule o
     where n.section_id = v_new_sec and o.section_id = v_sec.id and o.added_date = n.added_date;
  end loop;

  update sessions set is_active = false where id = v_old.id;
  return v_new_id;
end $$;

-- ───────────────────────── keyword search (full-text) ─────────────────────────
-- Used by the optional website; the app searches its offline copy the same way.
create function public.search_notes(p_query text)
returns table (session_id uuid, session_title text, session_type text, is_active boolean,
               section_id uuid, section_title text, bullet_id uuid, snippet text, date_added date)
language sql stable security invoker set search_path = public as $$
  select s.id, s.title, s.type, s.is_active, sec.id, sec.title, b.id, b.content, b.date_added
  from sessions s
  left join sections sec on sec.session_id = s.id
  left join bullets b on b.section_id = sec.id
  where s.title ilike '%' || p_query || '%'
     or sec.title ilike '%' || p_query || '%'
     or b.content ilike '%' || p_query || '%'
     or to_tsvector('simple', coalesce(b.content, '')) @@ websearch_to_tsquery('simple', p_query)
  order by s.is_active desc, s.updated_at desc, sec.position, b.position
  limit 200
$$;

-- ───────────────────────── Linking (RAG) ─────────────────────────
-- Text that a row's embedding is made from. Child = section title + every point shown
-- in it (its own and linked). Parent = a short summary: title, section titles and the
-- first point of each section — not the full text.
create function public.section_embed_text(p_section_id uuid)
returns text
language sql stable security invoker set search_path = public as $$
  select s.title || ' › ' || sec.title || E'\n' || coalesce((
    select string_agg('- ' || x.content, E'\n' order by x.position)
    from (
      select b.content, b.position from bullets b where b.section_id = sec.id
      union all
      select b.content, l.position from bullet_links l join bullets b on b.id = l.bullet_id
       where l.section_id = sec.id
    ) x), '')
  from sections sec join sessions s on s.id = sec.session_id
  where sec.id = p_section_id
$$;

create function public.session_embed_text(p_session_id uuid)
returns text
language sql stable security invoker set search_path = public as $$
  select s.title || E'\n' || coalesce((
    select string_agg(sec.title || coalesce(': ' || left((
             select b.content from bullets b where b.section_id = sec.id order by b.position limit 1), 160), ''),
           E'\n' order by sec.position)
    from sections sec where sec.session_id = s.id), '')
  from sessions s where s.id = p_session_id
$$;

-- Every row that should have an embedding, with its current text.
create view public.rag_rows with (security_invoker = true) as
  select 'section'::text as kind, sec.id, sec.session_id, sec.user_id,
         public.section_embed_text(sec.id) as content,
         sec.embedding_model, sec.embedding_hash, sec.embedding_staging_model, sec.embedding_staging_hash,
         (select count(*) from public.bullets b where b.section_id = sec.id)
           + (select count(*) from public.bullet_links l where l.section_id = sec.id) as note_count
  from public.sections sec
  union all
  select 'session'::text, s.id, s.id, s.user_id,
         public.session_embed_text(s.id),
         s.embedding_model, s.embedding_hash, s.embedding_staging_model, s.embedding_staging_hash,
         0
  from public.sessions s
  where exists (select 1 from public.sections sec where sec.session_id = s.id);

-- What the Settings banner shows.
create function public.rag_status()
returns jsonb
language plpgsql stable security invoker set search_path = public as $$
declare
  v settings%rowtype;
  v_total int; v_missing_rows int; v_missing_notes int; v_staged int; v_embedded int;
begin
  select * into v from settings where user_id = auth.uid();
  select count(*),
         count(*) filter (where embedding_model is null or embedding_model is distinct from v.rag_active_model),
         coalesce(sum(note_count) filter (where embedding_model is null
                                             or embedding_model is distinct from v.rag_active_model), 0),
         count(*) filter (where embedding_staging_model is not null
                            and embedding_staging_model = v.rag_selected_model),
         count(*) filter (where embedding_model is not null)
    into v_total, v_missing_rows, v_missing_notes, v_staged, v_embedded
    from rag_rows;
  return jsonb_build_object(
    'enabled', coalesce(v.rag_enabled, false),
    'selected_model', v.rag_selected_model,
    'active_model', v.rag_active_model,
    'total_rows', v_total,
    'missing_rows', v_missing_rows,
    'missing_notes', v_missing_notes,
    'staged_rows', v_staged,
    'embedded_rows', v_embedded,
    'ready', coalesce(v.rag_enabled, false) and v.rag_active_model is not null
             and v.rag_selected_model is not distinct from v.rag_active_model and v_missing_rows = 0);
end $$;

-- Pick the embedding model. Only while Linking is on. If nothing has been embedded
-- yet there is nothing to rebuild, so the new model becomes the active one at once.
create function public.rag_set_model(p_model text)
returns jsonb
language plpgsql security invoker set search_path = public as $$
declare v settings%rowtype;
begin
  select * into v from settings where user_id = auth.uid();
  if not found or not v.rag_enabled then
    raise exception 'The embedding model can only be changed while Linking is on.';
  end if;
  if not exists (select 1 from rag_rows where embedding_model is not null) then
    perform set_config('ewe.rag_internal', '1', true);
    update settings set rag_selected_model = p_model, rag_active_model = p_model where user_id = auth.uid();
    perform set_config('ewe.rag_internal', '0', true);
  else
    update settings set rag_selected_model = p_model where user_id = auth.uid();
  end if;
  return rag_status();
end $$;

-- "Switch back to <previous>": no rebuild, everything works as before.
create function public.rag_switch_back()
returns jsonb
language plpgsql security invoker set search_path = public as $$
begin
  update settings set rag_selected_model = rag_active_model
   where user_id = auth.uid() and rag_active_model is not null;
  update sections set embedding_staging = null, embedding_staging_model = null, embedding_staging_hash = null
   where embedding_staging_model is not null;
  update sessions set embedding_staging = null, embedding_staging_model = null, embedding_staging_hash = null
   where embedding_staging_model is not null;
  return rag_status();
end $$;

-- Rows that still need an embedding.
--   p_staging = false: rows missing for the active model, or whose text changed.
--   p_staging = true : "Rebuild all" — rows not yet staged with the selected model.
create function public.rag_pending(p_staging boolean, p_limit int)
returns table (kind text, id uuid, content text, hash text, remaining bigint)
language plpgsql stable security invoker set search_path = public as $$
declare v settings%rowtype;
begin
  select * into v from settings where user_id = auth.uid();
  return query
  with todo as (
    select r.kind, r.id, r.content, md5(r.content) as hash
    from rag_rows r
    where case when p_staging
               then r.embedding_staging_model is distinct from v.rag_selected_model
                    or r.embedding_staging_hash is distinct from md5(r.content)
               else r.embedding_model is distinct from v.rag_active_model
                    or r.embedding_hash is distinct from md5(r.content) end
  )
  select t.kind, t.id, t.content, t.hash, (select count(*) from todo) from todo t limit p_limit;
end $$;

-- Save embeddings. p_rows: [{ "kind": "section"|"session", "id": uuid, "hash": text,
--                             "embedding": [numbers] }]
create function public.rag_store(p_rows jsonb, p_model text, p_staging boolean)
returns int
language plpgsql security invoker set search_path = public, extensions as $$
declare
  r jsonb; v_vec extensions.vector; v_n int := 0; v settings%rowtype;
begin
  select * into v from settings where user_id = auth.uid();
  if p_staging and p_model is distinct from v.rag_selected_model then
    raise exception 'Embedding model changed during the rebuild — start it again.';
  end if;
  if not p_staging and p_model is distinct from v.rag_active_model then
    raise exception 'These embeddings were not made with the active model.';
  end if;
  for r in select * from jsonb_array_elements(p_rows) loop
    select array_agg(e::double precision order by ord)::extensions.vector into v_vec
      from jsonb_array_elements_text(r -> 'embedding') with ordinality as t(e, ord);
    if r ->> 'kind' = 'section' then
      if p_staging then
        update sections set embedding_staging = v_vec, embedding_staging_model = p_model,
               embedding_staging_hash = r ->> 'hash' where id = (r ->> 'id')::uuid;
      else
        update sections set embedding = v_vec, embedding_model = p_model,
               embedding_hash = r ->> 'hash' where id = (r ->> 'id')::uuid;
      end if;
    else
      if p_staging then
        update sessions set embedding_staging = v_vec, embedding_staging_model = p_model,
               embedding_staging_hash = r ->> 'hash' where id = (r ->> 'id')::uuid;
      else
        update sessions set embedding = v_vec, embedding_model = p_model,
               embedding_hash = r ->> 'hash' where id = (r ->> 'id')::uuid;
      end if;
    end if;
    v_n := v_n + 1;
  end loop;
  return v_n;
end $$;

-- End of "Rebuild all": swap the staged embeddings in, all at once, and only when
-- every row is done. After this "Switch back" is gone for good.
create function public.rag_swap()
returns jsonb
language plpgsql security invoker set search_path = public as $$
declare v settings%rowtype;
begin
  select * into v from settings where user_id = auth.uid();
  if v.rag_selected_model is null then raise exception 'No embedding model selected.'; end if;
  if exists (select 1 from rag_rows r
              where r.embedding_staging_model is distinct from v.rag_selected_model) then
    raise exception 'Rebuild is not finished yet.';
  end if;
  update sections set embedding = embedding_staging, embedding_model = embedding_staging_model,
         embedding_hash = embedding_staging_hash,
         embedding_staging = null, embedding_staging_model = null, embedding_staging_hash = null
   where embedding_staging_model is not null;
  update sessions set embedding = embedding_staging, embedding_model = embedding_staging_model,
         embedding_hash = embedding_staging_hash,
         embedding_staging = null, embedding_staging_model = null, embedding_staging_hash = null
   where embedding_staging_model is not null;
  perform set_config('ewe.rag_internal', '1', true);
  update settings set rag_active_model = rag_selected_model where user_id = auth.uid();
  perform set_config('ewe.rag_internal', '0', true);
  return rag_status();
end $$;

create function public.to_vector(p_embedding jsonb)
returns extensions.vector
language sql immutable set search_path = public, extensions as $$
  select array_agg(e::double precision order by ord)::extensions.vector
  from jsonb_array_elements_text(p_embedding) with ordinality as t(e, ord)
$$;

-- Candidate sections for a group of new notes. With p_placement = true only Active
-- Topic sections outside the current session qualify; otherwise everything (search).
create function public.match_sections(p_embedding jsonb, p_exclude_session uuid, p_limit int, p_placement boolean)
returns table (section_id uuid, session_id uuid, session_title text, session_type text, is_active boolean,
               section_title text, similarity double precision, preview text)
language sql stable security invoker set search_path = public, extensions as $$
  select sec.id, s.id, s.title, s.type, s.is_active, sec.title,
         1 - (sec.embedding <=> public.to_vector(p_embedding)) as similarity,
         left(public.section_embed_text(sec.id), 600)
  from sections sec
  join sessions s on s.id = sec.session_id
  join settings st on st.user_id = sec.user_id
  where sec.embedding is not null
    and sec.embedding_model = st.rag_active_model
    and (p_exclude_session is null or s.id <> p_exclude_session)
    and (not p_placement or (s.type = 'topic' and s.is_active))
  order by sec.embedding <=> public.to_vector(p_embedding)
  limit p_limit
$$;

-- Parent fallback: Topics that match even when no single section does.
create function public.match_topics(p_embedding jsonb, p_exclude_session uuid, p_limit int)
returns table (session_id uuid, session_title text, similarity double precision, preview text)
language sql stable security invoker set search_path = public, extensions as $$
  select s.id, s.title, 1 - (s.embedding <=> public.to_vector(p_embedding)),
         left(public.session_embed_text(s.id), 400)
  from sessions s
  join settings st on st.user_id = s.user_id
  where s.embedding is not null
    and s.embedding_model = st.rag_active_model
    and s.type = 'topic' and s.is_active
    and (p_exclude_session is null or s.id <> p_exclude_session)
  order by s.embedding <=> public.to_vector(p_embedding)
  limit p_limit
$$;

-- Show points inside a Topic as well (stored once). Either an existing section of an
-- Active Topic, or a new section in one. Returns the section id.
create function public.link_bullets(p_bullet_ids uuid[], p_section_id uuid, p_topic_id uuid, p_title text)
returns uuid
language plpgsql security invoker set search_path = public as $$
declare
  v_topic sessions%rowtype;
  v_sec   uuid := p_section_id;
  v_pos   double precision;
  v_id    uuid;
begin
  if v_sec is not null then
    select s.* into v_topic from sessions s join sections sec on sec.session_id = s.id where sec.id = v_sec;
  else
    select * into v_topic from sessions where id = p_topic_id;
  end if;
  if not found then raise exception 'Target topic not found'; end if;
  if v_topic.type <> 'topic' then raise exception 'Points are only ever added into Topics.'; end if;
  if not v_topic.is_active then raise exception 'Inactive topics receive no automatic additions.'; end if;
  if exists (select 1 from bullets b join sections sec on sec.id = b.section_id
              where b.id = any(p_bullet_ids) and sec.session_id = v_topic.id) then
    raise exception 'These points already belong to that topic.';
  end if;

  if v_sec is null then
    insert into sections (session_id, title, position)
    values (v_topic.id, coalesce(nullif(trim(p_title), ''), 'Related'),
            coalesce((select max(position) from sections where session_id = v_topic.id), 0) + 1)
    returning id into v_sec;
  end if;

  select greatest(coalesce((select max(position) from bullets where section_id = v_sec), 0),
                  coalesce((select max(position) from bullet_links where section_id = v_sec), 0))
    into v_pos;
  for v_id in select b.id from bullets b where b.id = any(p_bullet_ids) order by b.position loop
    v_pos := v_pos + 1;
    insert into bullet_links (bullet_id, section_id, position) values (v_id, v_sec, v_pos)
    on conflict (bullet_id, section_id) do nothing;
  end loop;
  update sessions set updated_at = now() where id = v_topic.id;
  return v_sec;
end $$;

-- ───────────────────────── row level security ─────────────────────────
alter table public.settings          enable row level security;
alter table public.sessions          enable row level security;
alter table public.messages          enable row level security;
alter table public.sections          enable row level security;
alter table public.bullets           enable row level security;
alter table public.bullet_links      enable row level security;
alter table public.revision_schedule enable row level security;
alter table public.revision_log      enable row level security;

create policy own_settings on public.settings          for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_sessions on public.sessions          for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_messages on public.messages          for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_sections on public.sections          for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_bullets  on public.bullets           for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_links    on public.bullet_links      for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_schedule on public.revision_schedule for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_log      on public.revision_log      for all using (user_id = auth.uid()) with check (user_id = auth.uid());

grant usage on schema public to authenticated;
grant select, insert, update, delete on all tables in schema public to authenticated;
grant execute on all functions in schema public to authenticated;

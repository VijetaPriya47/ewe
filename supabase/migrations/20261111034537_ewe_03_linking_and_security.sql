-- ewe — linking (RAG) and row-level security

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

-- ewe — nested bullets.
--   * level: 0 = a top-level point, 1 = a sub-point, 2 = a sub-sub-point. Points keep their order
--     (position); the level only says how far a point is indented under the one before it.
--   * Set by the AI when making notes (text points only) and by you with indent / outdent.

alter table public.bullets
  add column level smallint not null default 0 check (level between 0 and 2);

create or replace function public.apply_notes(p_session_id uuid, p_message_id uuid, p_payload jsonb, p_today date)
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
  v_kind      text;
  v_extra     jsonb;
  v_tables    int;
  v_codes     int;
  v_new_tables int := 0;
  v_new_codes  int := 0;
  v_figs       int;
  v_new_figs   int := 0;
  v_sec_important boolean;
  v_diag      int;
  v_new_diags int := 0;
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
    v_sec_important := coalesce((v_sec ->> 'important')::boolean, false);
    if v_is_new then
      v_title := coalesce(nullif(trim(v_sec ->> 'title'), ''), 'Notes');
      insert into sections (session_id, title, position, important)
      values (p_session_id, v_title,
              coalesce((select max(position) from sections where session_id = p_session_id), 0) + 1, v_sec_important)
      returning id into v_sec_id;
      v_new_secs := v_new_secs + 1;
    else
      -- the AI can mark an existing section important, never unmark it
      update sections set updated_at = now(), important = important or v_sec_important
       where id = v_sec_id returning title into v_title;
    end if;

    v_after := null; v_last_anchor := null; v_last_inserted := null; v_added := 0; v_inline := 0; v_diag := 0; v_tables := 0; v_codes := 0; v_figs := 0;
    for v_bul in select * from jsonb_array_elements(v_sec -> 'bullets') loop
      if length(trim(coalesce(v_bul ->> 'text', ''))) = 0 then continue; end if;
      v_kind := case when v_bul ->> 'kind' in ('mermaid', 'code', 'table', 'figure') then v_bul ->> 'kind' else 'text' end;
      -- a figure is a picture in your own storage folder: "<your id>/…"
      if v_kind = 'figure' and (v_bul ->> 'text') not like auth.uid()::text || '/%' then continue; end if;

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

      insert into bullets (section_id, content, kind, lang, caption, important, level, position, date_added, origin_session_id, origin_message_id)
      values (v_sec_id,
              -- code keeps its indentation: only blank lines around it are dropped
              case when v_kind = 'code' then regexp_replace(regexp_replace(v_bul ->> 'text', '^\s*\n', ''), '\s+$', '')
                   else trim(v_bul ->> 'text') end,
              v_kind,
              case when v_kind = 'code' then nullif(lower(trim(coalesce(v_bul ->> 'lang', ''))), '') end,
              nullif(left(trim(coalesce(v_bul ->> 'caption', '')), 200), ''),
              coalesce((v_bul ->> 'important')::boolean, false),
              -- only text points nest (0–2); the app never shows one more than a level below the point before it
              case when v_kind = 'text' then least(greatest(coalesce(
                case when (v_bul ->> 'level') ~ '^-?[0-9]+$' then (v_bul ->> 'level')::int end, 0), 0), 2) else 0 end,
              v_pos, p_today, p_session_id, p_message_id)
      returning id into v_new_id;
      v_new_buls := v_new_buls + 1;
      v_added := v_added + 1;
      if v_kind = 'mermaid' then v_diag := v_diag + 1; v_new_diags := v_new_diags + 1; end if;
      if v_kind = 'table' then v_tables := v_tables + 1; v_new_tables := v_new_tables + 1; end if;
      if v_kind = 'code' then v_codes := v_codes + 1; v_new_codes := v_new_codes + 1; end if;
      if v_kind = 'figure' then v_figs := v_figs + 1; v_new_figs := v_new_figs + 1; end if;

      v_last_inserted := v_new_id;
    end loop;

    v_detail := v_detail || jsonb_build_object(
      'section_id', v_sec_id, 'title', v_title, 'is_new', v_is_new, 'added', v_added, 'inline', v_inline, 'diagrams', v_diag,
      'tables', v_tables, 'code', v_codes, 'figures', v_figs);
  end loop;

  update sessions set updated_at = now() where id = p_session_id;
  return jsonb_build_object('new_sections', v_new_secs, 'new_bullets', v_new_buls, 'new_diagrams', v_new_diags,
    'new_tables', v_new_tables, 'new_code', v_new_codes, 'new_figures', v_new_figs, 'sections', v_detail);
end $$;

create or replace function public.recreate_session(p_session_id uuid)
returns uuid
language plpgsql security invoker set search_path = public as $$
declare
  v_old     sessions%rowtype;
  v_new_id  uuid;
  v_sec     record;
  v_new_sec uuid;
  v_ref     record;
  v_new_ref uuid;
begin
  select * into v_old from sessions where id = p_session_id;
  if not found then raise exception 'Session not found'; end if;

  insert into sessions (title, type, recreated_from)
  values (v_old.title, case when v_old.type = 'topic' then 'project' else 'topic' end, v_old.id)
  returning id into v_new_id;

  for v_sec in select * from sections where session_id = v_old.id order by position loop
    if not exists (select 1 from bullets where section_id = v_sec.id) then continue; end if;

    insert into sections (session_id, title, position, important)
    values (v_new_id, v_sec.title, v_sec.position, v_sec.important) returning id into v_new_sec;

    insert into bullets (section_id, content, kind, lang, caption, important, highlight, level, position, date_added, origin_session_id, origin_message_id)
    select v_new_sec, b.content, b.kind, b.lang, b.caption, b.important, b.highlight, b.level, b.position, b.date_added,
           case when b.origin_session_id = v_old.id then v_new_id else b.origin_session_id end,
           b.origin_message_id
    from bullets b where b.section_id = v_sec.id;

    update revision_schedule n
       set interval_days = o.interval_days, revisions_done = o.revisions_done,
           next_due_date = o.next_due_date, last_revised = o.last_revised
      from revision_schedule o
     where n.section_id = v_new_sec and o.section_id = v_sec.id and o.added_date = n.added_date;
  end loop;

  -- References keep their numbers; the [n] marks in the copied notes point to the copies.
  for v_ref in select * from session_references where session_id = v_old.id order by number loop
    insert into session_references (session_id, number, url, title, description, date_added, origin_message_id)
    values (v_new_id, v_ref.number, v_ref.url, v_ref.title, v_ref.description, v_ref.date_added, v_ref.origin_message_id)
    returning id into v_new_ref;
    update bullets set content = replace(content, '[[ref:' || v_ref.id || ']]', '[[ref:' || v_new_ref || ']]')
     where section_id in (select id from sections where session_id = v_new_id)
       and content like '%[[ref:' || v_ref.id || ']]%';
  end loop;

  insert into session_summaries (session_id, kind, title, mermaid, note, origin_message_id, created_at)
  select v_new_id, kind, title, mermaid, note, origin_message_id, created_at from session_summaries where session_id = v_old.id;

  update sessions set is_active = false where id = v_old.id;
  return v_new_id;
end $$;

-- ewe — Mermaid diagrams in notes.
-- A diagram is a bullet whose kind is 'mermaid' and whose content is the Mermaid code.
-- Text bullets still carry every point; diagrams are extra, so revision and read-aloud skip them.

alter table public.bullets
  add column kind text not null default 'text' check (kind in ('text', 'mermaid'));

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

    v_after := null; v_last_anchor := null; v_last_inserted := null; v_added := 0; v_inline := 0; v_diag := 0;
    for v_bul in select * from jsonb_array_elements(v_sec -> 'bullets') loop
      if length(trim(coalesce(v_bul ->> 'text', ''))) = 0 then continue; end if;
      v_kind := case when v_bul ->> 'kind' = 'mermaid' then 'mermaid' else 'text' end;

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

      insert into bullets (section_id, content, kind, position, date_added, origin_session_id, origin_message_id)
      values (v_sec_id, trim(v_bul ->> 'text'), v_kind, v_pos, p_today, p_session_id, p_message_id)
      returning id into v_new_id;
      v_new_buls := v_new_buls + 1;
      v_added := v_added + 1;
      if v_kind = 'mermaid' then v_diag := v_diag + 1; v_new_diags := v_new_diags + 1; end if;

      v_last_inserted := v_new_id;
    end loop;

    v_detail := v_detail || jsonb_build_object(
      'section_id', v_sec_id, 'title', v_title, 'is_new', v_is_new, 'added', v_added, 'inline', v_inline, 'diagrams', v_diag);
  end loop;

  update sessions set updated_at = now() where id = p_session_id;
  return jsonb_build_object('new_sections', v_new_secs, 'new_bullets', v_new_buls, 'new_diagrams', v_new_diags, 'sections', v_detail);
end $$;

create or replace function public.recreate_session(p_session_id uuid)
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

    insert into bullets (section_id, content, kind, position, date_added, origin_session_id, origin_message_id)
    select v_new_sec, b.content, b.kind, b.position, b.date_added,
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


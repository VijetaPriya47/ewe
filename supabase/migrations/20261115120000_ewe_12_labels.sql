-- ewe — labels on sessions.
--   * labels: short tags you add to a session (at most 10, each 1–24 characters), to filter and sort the
--     session list. Editing them does not change updated_at, so the list order (latest updated) holds.
--   * "Recreate as Topic/Project" keeps the labels.

alter table public.sessions
  add column labels text[] not null default '{}' check (cardinality(labels) <= 10);

-- each label is 1–24 characters with no space at either end (a check cannot look inside an array)
create function public.sessions_check_labels() returns trigger
language plpgsql set search_path = public as $$
declare l text;
begin
  foreach l in array new.labels loop
    if l is null or length(l) < 1 or length(l) > 24 or l <> trim(l) then
      raise exception 'A label is 1 to 24 characters, without spaces at either end';
    end if;
  end loop;
  return new;
end $$;
create trigger sessions_labels_check before insert or update of labels on public.sessions
  for each row execute function public.sessions_check_labels();
revoke execute on function public.sessions_check_labels() from public, anon, authenticated;

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

  insert into sessions (title, type, recreated_from, labels)
  values (v_old.title, case when v_old.type = 'topic' then 'project' else 'topic' end, v_old.id, v_old.labels)
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

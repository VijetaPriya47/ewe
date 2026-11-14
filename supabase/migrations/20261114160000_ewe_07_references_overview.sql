-- ewe — References, Overview summaries and message modes.
--
--   * session_references: the links of a session. Each gets a number (1, 2, 3 …) that never changes,
--     and a link is stored once per session (compared after tidying, see ref_url_key).
--     Notes cite a reference as [[ref:<id>]] in their text; the app shows it as a [n] chip.
--   * session_summaries: diagrams for the Overview tab — your own summaries ('upload') and the
--     AI overview ('ai_overview').
--   * messages.mode: where a chat message went — 'notes', 'reference' or 'summary'.

alter table public.messages
  add column mode text not null default 'notes' check (mode in ('notes', 'reference', 'summary'));

-- The same page written slightly differently counts as one link:
-- scheme, "www.", default ports, #fragments, trailing "/" and tracking parameters are ignored.
create function public.ref_url_key(p_url text)
returns text
language plpgsql immutable set search_path = public as $$
declare
  v_u     text := trim(p_url);
  v_m     text[];
  v_host  text;
  v_path  text;
  v_query text;
  v_kept  text[] := '{}';
  v_p     text;
begin
  v_u := regexp_replace(v_u, '#.*$', '');
  v_u := regexp_replace(v_u, '^[a-zA-Z][a-zA-Z0-9+.-]*://', '');
  v_m := regexp_match(v_u, '^([^/?]*)([^?]*)(?:\?(.*))?$');
  v_host := lower(coalesce(v_m[1], ''));
  v_host := regexp_replace(v_host, '^www\.', '');
  v_host := regexp_replace(v_host, ':(80|443)$', '');
  v_path := regexp_replace(coalesce(v_m[2], ''), '/+$', '');
  v_query := coalesce(v_m[3], '');
  if v_query <> '' then
    foreach v_p in array string_to_array(v_query, '&') loop
      if v_p <> '' and v_p !~* '^(utm_[a-z]+|fbclid|gclid|mc_cid|mc_eid|ref_src)(=|$)' then
        v_kept := v_kept || v_p;
      end if;
    end loop;
  end if;
  return v_host || v_path || case when array_length(v_kept, 1) > 0 then '?' || array_to_string(v_kept, '&') else '' end;
end $$;

create table public.session_references (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users(id),
  session_id         uuid not null references public.sessions(id),
  number             int  not null,          -- set by the trigger below (NOT NULL is checked after it)
  url                text not null check (url ~* '^https?://[^\s]+$'),
  url_key            text not null,
  title              text not null default '',
  description        text not null default '',
  date_added         date not null default current_date,
  origin_message_id  uuid references public.messages(id),
  created_at         timestamptz not null default now(),
  unique (session_id, url_key),
  unique (session_id, number)
);
create index session_references_session_idx on public.session_references (session_id, number);

-- Fills in the link's key, a title if none was given, and (for a new link) the next number.
-- Numbers and the session never change afterwards.
create function public.session_references_fill() returns trigger
language plpgsql set search_path = public as $$
begin
  new.url := trim(new.url);
  new.url_key := ref_url_key(new.url);
  new.title := trim(coalesce(new.title, ''));
  new.description := trim(coalesce(new.description, ''));
  if new.title = '' then new.title := split_part(new.url_key, '/', 1); end if;
  if tg_op = 'UPDATE' then
    new.number := old.number;
    new.session_id := old.session_id;
    new.user_id := old.user_id;
  elsif new.number is null then
    perform 1 from sessions where id = new.session_id for update;  -- one numbering at a time per session
    select coalesce(max(number), 0) + 1 into new.number from session_references where session_id = new.session_id;
  end if;
  return new;
end $$;
create trigger session_references_fill before insert or update on public.session_references
  for each row execute function public.session_references_fill();

create table public.session_summaries (
  id                 uuid primary key default gen_random_uuid(),
  user_id            uuid not null default auth.uid() references auth.users(id),
  session_id         uuid not null references public.sessions(id),
  kind               text not null check (kind in ('upload', 'ai_overview')),
  title              text not null default '',
  mermaid            text not null default '',
  note               text not null default '',
  origin_message_id  uuid references public.messages(id),
  created_at         timestamptz not null default now()
);
create index session_summaries_session_idx on public.session_summaries (session_id, created_at desc);

-- Saves links for a session. A link already saved keeps its number (an empty title or description
-- is filled in). p_refs: [{ "key": text, "url": text, "title": text, "description": text }]
-- Returns [{ "key", "id", "number", "title", "url", "is_new" }] in the same order.
create function public.upsert_references(p_session_id uuid, p_message_id uuid, p_refs jsonb, p_today date)
returns jsonb
language plpgsql security invoker set search_path = public as $$
declare
  v_r    jsonb;
  v_row  session_references%rowtype;
  v_new  boolean;
  v_out  jsonb := '[]'::jsonb;
begin
  for v_r in select * from jsonb_array_elements(coalesce(p_refs, '[]'::jsonb)) loop
    if coalesce(v_r ->> 'url', '') !~* '^https?://[^\s]+$' then continue; end if;
    select * into v_row from session_references
     where session_id = p_session_id and url_key = ref_url_key(v_r ->> 'url');
    v_new := not found;
    if v_new then
      insert into session_references (session_id, url, title, description, date_added, origin_message_id)
      values (p_session_id, v_r ->> 'url', coalesce(v_r ->> 'title', ''), coalesce(v_r ->> 'description', ''), p_today, p_message_id)
      returning * into v_row;
    elsif (v_row.title = split_part(v_row.url_key, '/', 1) and coalesce(v_r ->> 'title', '') <> '')
       or (v_row.description = '' and coalesce(v_r ->> 'description', '') <> '') then
      update session_references
         set title = case when title = split_part(url_key, '/', 1) and coalesce(v_r ->> 'title', '') <> '' then v_r ->> 'title' else title end,
             description = case when description = '' then coalesce(v_r ->> 'description', '') else description end
       where id = v_row.id
      returning * into v_row;
    end if;
    v_out := v_out || jsonb_build_object('key', v_r ->> 'key', 'id', v_row.id, 'number', v_row.number,
                                         'title', v_row.title, 'url', v_row.url, 'is_new', v_new);
  end loop;
  return v_out;
end $$;

-- Embeddings should not see the [[ref:…]] marks.
create or replace function public.section_embed_text(p_section_id uuid)
returns text
language sql stable security invoker set search_path = public as $$
  select s.title || ' › ' || sec.title || E'\n' || coalesce((
    select string_agg('- ' || regexp_replace(x.content, '\[\[ref:[0-9a-fA-F-]+\]\]', '', 'g'), E'\n' order by x.position)
    from (
      select b.content, b.position from bullets b where b.section_id = sec.id
      union all
      select b.content, l.position from bullet_links l join bullets b on b.id = l.bullet_id
       where l.section_id = sec.id
    ) x), '')
  from sections sec join sessions s on s.id = sec.session_id
  where sec.id = p_section_id
$$;

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

    insert into bullets (section_id, content, kind, lang, important, highlight, position, date_added, origin_session_id, origin_message_id)
    select v_new_sec, b.content, b.kind, b.lang, b.important, b.highlight, b.position, b.date_added,
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

alter table public.session_references enable row level security;
alter table public.session_summaries  enable row level security;
create policy own_references on public.session_references for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_summaries  on public.session_summaries  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
grant select, insert, update, delete on public.session_references, public.session_summaries to authenticated;
revoke execute on function public.session_references_fill() from public, anon, authenticated;

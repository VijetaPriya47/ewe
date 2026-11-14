-- Behaviour tests for the schema. Run via tests/run_local.sh.
insert into auth.users values ('11111111-1111-1111-1111-111111111111'), ('22222222-2222-2222-2222-222222222222');
set role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-1111-1111-1111-111111111111"}', false);

create function pg_temp.fails(p_sql text) returns boolean language plpgsql as $$
begin execute p_sql; return false; exception when others then return true; end $$;
create function pg_temp.ok(p_cond boolean, p_msg text) returns void language plpgsql as $$
begin if p_cond is not true then raise exception 'FAILED: %', p_msg; end if; end $$;

do $$
declare
  v_cdn uuid; v_sud uuid; v_msg uuid; v_res jsonb; v_basics uuid; v_b1 uuid; v_b2 uuid;
  v_order text; v_new uuid; v_sched uuid; v_n int; v_st jsonb; v_sec uuid; v_sb uuid; v_cnt int;
begin
  insert into settings default values;
  insert into sessions (title, type) values ('CDN', 'topic') returning id into v_cdn;
  insert into sessions (title, type) values ('Making a Sudoku', 'project') returning id into v_sud;

  -- type locked, no delete
  perform pg_temp.ok(pg_temp.fails(format('update sessions set type = ''project'' where id = %L', v_cdn)), 'type is locked');
  perform pg_temp.ok(pg_temp.fails(format('delete from sessions where id = %L', v_cdn)), 'sessions cannot be deleted');
  update sessions set is_active = false where id = v_cdn;
  update sessions set is_active = true where id = v_cdn;

  -- read-only chat
  insert into messages (session_id, role, content, image_count) values (v_cdn, 'user', '', 3) returning id into v_msg;
  perform pg_temp.ok(pg_temp.fails(format('update messages set content = ''x'' where id = %L', v_msg)), 'messages cannot be edited');
  perform pg_temp.ok(pg_temp.fails(format('delete from messages where id = %L', v_msg)), 'messages cannot be deleted');

  -- first upload: two new sections
  v_res := apply_notes(v_cdn, v_msg, '{"sections":[
     {"section_id":null,"title":"Basics","bullets":[{"text":"A"},{"text":"B"},{"text":"C"}]},
     {"section_id":null,"title":"Empty","bullets":[{"text":"  "}]},
     {"section_id":null,"title":"Edge","bullets":[{"text":"E1"}]}]}', date '2026-11-08');
  perform pg_temp.ok((v_res->>'new_bullets')::int = 4 and (v_res->>'new_sections')::int = 2, 'apply_notes counts: ' || v_res::text);
  select id into v_basics from sections where session_id = v_cdn and title = 'Basics';
  select id into v_b1 from bullets where section_id = v_basics and content = 'A';

  -- second upload: inline after A (two bullets, same anchor, order kept), one at END
  v_res := apply_notes(v_cdn, v_msg, jsonb_build_object('sections', jsonb_build_array(jsonb_build_object(
     'section_id', v_basics, 'title', 'Basics', 'bullets', jsonb_build_array(
        jsonb_build_object('text', 'A1', 'after_bullet_id', v_b1),
        jsonb_build_object('text', 'A2', 'after_bullet_id', v_b1),
        jsonb_build_object('text', 'Z', 'after_bullet_id', null))))), date '2026-11-10');
  select string_agg(content, ',' order by position) into v_order from bullets where section_id = v_basics;
  perform pg_temp.ok(v_order = 'A,A1,A2,B,C,Z', 'inline order: ' || v_order);
  perform pg_temp.ok((v_res->'sections'->0->>'inline')::int = 2, 'inline count: ' || v_res::text);

  -- schedule: one batch per (section, date)
  select count(*) into v_cnt from revision_schedule r join sections s on s.id = r.section_id where s.session_id = v_cdn;
  perform pg_temp.ok(v_cnt = 3, 'schedule batches = ' || v_cnt);
  select id into v_sched from revision_schedule where section_id = v_basics and added_date = '2026-11-08';
  perform pg_temp.ok((select next_due_date from revision_schedule where id = v_sched) = '2026-11-09', 'first due next day');
  v_n := mark_listened(array[v_sched], date '2026-11-09');
  v_n := mark_listened(array[v_sched], date '2026-11-09');  -- idempotent
  perform pg_temp.ok((select interval_days = 2 and next_due_date = '2026-11-11' and revisions_done = 1
                      from revision_schedule where id = v_sched), 'interval doubles from today');
  update settings set interval_cap_days = 2;
  v_n := mark_listened(array[v_sched], date '2026-11-11');
  perform pg_temp.ok((select interval_days = 2 from revision_schedule where id = v_sched), 'interval capped');
  perform restart_revision(v_cdn, date '2026-11-12');
  perform pg_temp.ok((select interval_days = 1 and revisions_done = 0 and next_due_date = '2026-11-12'
                      from revision_schedule where id = v_sched), 'restart from day 1');

  -- project: section_id is ignored, always appended as new section
  v_res := apply_notes(v_sud, null, jsonb_build_object('sections', jsonb_build_array(jsonb_build_object(
     'section_id', v_basics, 'title', 'Grid', 'bullets', jsonb_build_array(jsonb_build_object('text', 'Cache static puzzle assets'))))), date '2026-11-09');
  perform pg_temp.ok((v_res->>'new_sections')::int = 1, 'project appends');
  -- diagrams are bullets of kind 'mermaid', counted separately
  v_res := apply_notes(v_sud, null, '{"sections":[{"section_id":null,"title":"Flow","bullets":[
     {"text":"Generator fills grid, then removes cells"},
     {"text":"flowchart TD\n  A[Fill grid] --> B[Remove cells]","kind":"mermaid"},
     {"text":"odd kind falls back to text","kind":"html"}]}]}', date '2026-11-09');
  perform pg_temp.ok((v_res->>'new_diagrams')::int = 1 and (v_res->>'new_bullets')::int = 3
                     and (v_res->'sections'->0->>'diagrams')::int = 1, 'diagram counts: ' || v_res::text);
  perform pg_temp.ok((select count(*) from bullets where kind = 'mermaid') = 1
                     and (select kind from bullets where content = 'odd kind falls back to text') = 'text', 'kinds stored');
  perform pg_temp.ok(pg_temp.fails('insert into bullets (section_id, content, kind, position, origin_session_id) select id, ''x'', ''svg'', 9, session_id from sections limit 1'), 'unknown kind rejected');
  delete from bullets where section_id in (select id from sections where title = 'Flow');

  -- code blocks, tables, important, highlight
  v_res := apply_notes(v_sud, null, '{"sections":[{"section_id":null,"title":"Code","important":true,"bullets":[
     {"text":"Solver uses backtracking","important":true},
     {"text":"def solve(g):\n    return g\n","kind":"code","lang":" Python "},
     {"text":"| Level | Clues |\n| --- | --- |\n| Easy | 36 |","kind":"table","lang":"ignored"}]}]}', date '2026-11-09');
  perform pg_temp.ok((v_res->>'new_tables')::int = 1 and (v_res->>'new_code')::int = 1
                     and (v_res->'sections'->0->>'code')::int = 1, 'rich counts: ' || v_res::text);
  perform pg_temp.ok((select important from sections where title = 'Code'), 'section important');
  perform pg_temp.ok((select lang = 'python' and content = 'def solve(g):' || chr(10) || '    return g' from bullets where kind = 'code'), 'code kept, lang normalised');
  perform pg_temp.ok((select lang is null from bullets where kind = 'table'), 'lang only for code');
  perform pg_temp.ok((select important from bullets where content = 'Solver uses backtracking'), 'bullet important');
  update bullets set highlight = 'yellow' where content = 'Solver uses backtracking';
  perform pg_temp.ok(pg_temp.fails('update bullets set highlight = ''blue'' where kind = ''table'''), 'only yellow or red');
  delete from bullets where section_id in (select id from sections where title = 'Code');
  select b.id into v_sb from bullets b join sections s on s.id = b.section_id where s.session_id = v_sud;

  -- linking rules
  perform pg_temp.ok(pg_temp.fails('update settings set rag_selected_model = ''openai:a'''), 'model locked while linking off');
  update settings set rag_enabled = true;
  v_st := rag_set_model('openai:small');
  perform pg_temp.ok(v_st->>'active_model' = 'openai:small' and (v_st->>'missing_rows')::int = 5 and not (v_st->>'ready')::boolean,
                     'first model becomes active, rows missing: ' || v_st::text);
  perform pg_temp.ok((v_st->>'missing_notes')::int = 8, 'missing notes: ' || v_st::text);
  perform rag_store((select jsonb_agg(jsonb_build_object('kind', kind, 'id', id, 'hash', hash,
            'embedding', case when content like 'CDN%' then '[1,0,0]'::jsonb else '[0,1,0]'::jsonb end))
            from rag_pending(false, 100)), 'openai:small', false);
  v_st := rag_status();
  perform pg_temp.ok((v_st->>'ready')::boolean, 'ready after rebuild: ' || v_st::text);
  perform pg_temp.ok((select count(*) from rag_pending(false, 100)) = 0, 'nothing pending');

  -- placement candidates: only active topics, never the current session
  select count(*) into v_cnt from match_sections('[1,0,0]', v_sud, 5, true);
  perform pg_temp.ok(v_cnt = 2, 'topic candidates = ' || v_cnt);
  select count(*) into v_cnt from match_sections('[1,0,0]', v_cdn, 5, true);
  perform pg_temp.ok(v_cnt = 0, 'projects are never placement targets');
  select count(*) into v_cnt from match_sections('[0,1,0]', null, 5, false);
  perform pg_temp.ok(v_cnt = 3, 'search sees every section');
  perform pg_temp.ok((select similarity > 0.99 from match_topics('[1,0,0]', v_sud, 3) limit 1), 'parent fallback');

  -- link a project point into the topic; never the other way
  v_sec := link_bullets(array[v_sb], v_basics, null, null);
  perform pg_temp.ok(v_sec = v_basics and (select count(*) from bullet_links where section_id = v_basics) = 1, 'linked');
  perform pg_temp.ok(pg_temp.fails(format('select link_bullets(array[%L::uuid], null, %L, ''x'')', v_b1, v_sud)), 'no links into projects');
  perform pg_temp.ok((select count(*) from rag_pending(false, 100)) = 1, 'linked section text changed → stale');
  v_sec := link_bullets(array[v_sb], null, v_cdn, 'Sudoku ideas');
  perform pg_temp.ok((select title from sections where id = v_sec) = 'Sudoku ideas', 'new section in topic');
  update sessions set is_active = false where id = v_cdn;
  perform pg_temp.ok(pg_temp.fails(format('select link_bullets(array[%L::uuid], %L, null, null)', v_sb, v_basics)), 'inactive topics get nothing');
  update sessions set is_active = true where id = v_cdn;

  -- model change → staging rebuild → swap; switch back
  v_st := rag_set_model('gemini:embed');
  perform pg_temp.ok(v_st->>'selected_model' = 'gemini:embed' and v_st->>'active_model' = 'openai:small' and not (v_st->>'ready')::boolean, 'mismatch pauses');
  v_st := rag_switch_back();
  perform pg_temp.ok(v_st->>'selected_model' = 'openai:small', 'switch back');
  v_st := rag_set_model('gemini:embed');
  perform pg_temp.ok(pg_temp.fails('select rag_swap()'), 'swap refused until every row is staged');
  perform rag_store((select jsonb_agg(jsonb_build_object('kind', kind, 'id', id, 'hash', hash, 'embedding', '[0,0,1]'::jsonb))
            from rag_pending(true, 100)), 'gemini:embed', true);
  perform pg_temp.ok((select count(*) from sections where embedding_model = 'openai:small') > 0, 'live index untouched during rebuild');
  v_st := rag_swap();
  perform pg_temp.ok(v_st->>'active_model' = 'gemini:embed' and (v_st->>'ready')::boolean, 'swap done: ' || v_st::text);
  perform pg_temp.ok(pg_temp.fails('update settings set rag_active_model = ''x'''), 'active model is protected');
  update settings set rag_enabled = false;
  perform pg_temp.ok(pg_temp.fails('select rag_set_model(''openai:small'')'), 'no model change while off');

  -- deleting a linked point's link removes an emptied section; deleting the bullet removes links
  delete from bullet_links where section_id = v_sec;
  perform pg_temp.ok(not exists (select 1 from sections where id = v_sec), 'empty section pruned');
  delete from bullets where id = v_sb;
  perform pg_temp.ok(not exists (select 1 from bullet_links) and not exists (select 1 from sections where session_id = v_sud), 'cascade + prune');

  -- recreate as project
  v_new := recreate_session(v_cdn);
  perform pg_temp.ok((select type = 'project' and recreated_from = v_cdn from sessions where id = v_new), 'recreated as project');
  perform pg_temp.ok((select not is_active from sessions where id = v_cdn), 'old one inactive');
  perform pg_temp.ok((select count(*) from bullets b join sections s on s.id = b.section_id where s.session_id = v_new) = 7, 'notes copied');
  perform pg_temp.ok((select min(date_added) from bullets b join sections s on s.id = b.section_id where s.session_id = v_new) = '2026-11-08', 'dates kept');

  perform pg_temp.ok((select count(*) from search_notes('edge')) >= 1, 'keyword search');
end $$;

-- another user sees nothing
select set_config('request.jwt.claims', '{"sub":"22222222-2222-2222-2222-222222222222"}', false);
do $$ begin
  perform pg_temp.ok((select count(*) from sessions) = 0 and (select count(*) from bullets) = 0, 'row level security');
end $$;
reset role;

// POST /functions/v1/linking — everything that needs embeddings (Phase 2).
//
// Body: { "action": "sync" | "link" | "search", "embedding": ProviderCfg, ... }
//   sync   { mode: "refresh" | "rebuild" }      embed what is missing / "Rebuild all" into staging
//   link   { session_id, message_id, placement: ProviderCfg }   place a message's new notes
//   search { query }                            sections closest in meaning to the query
//
// Keys are sent with each request and never stored.

import type { SupabaseClient } from "npm:@supabase/supabase-js@2.119.0";
import { HttpError, must, serve, userClient } from "../_shared/http.ts";
import { checkCfg, embed, generateJson, modelKey, type ProviderCfg } from "../_shared/providers.ts";
import { buildPlacementRequest, resolvePlacement, type SectionCandidate, type TopicCandidate } from "../_shared/placement.ts";

const BATCH = 32;
const TIME_BUDGET_MS = 100_000;  // stay well inside the function's wall-clock limit
const MIN_SIMILARITY = 0.2;      // below this a candidate is noise; the placement model decides the rest

interface Status { enabled: boolean; selected_model: string | null; active_model: string | null; ready: boolean; missing_rows: number }
interface Pending { kind: string; id: string; content: string; hash: string; remaining: number }

async function embedPending(db: SupabaseClient, cfg: ProviderCfg, staging: boolean, deadline: number): Promise<number> {
  for (;;) {
    const rows = must(await db.rpc("rag_pending", { p_staging: staging, p_limit: BATCH })) as Pending[];
    if (rows.length === 0) return 0;
    const vectors = await embed(cfg, rows.map((r) => r.content));
    must(await db.rpc("rag_store", {
      p_rows: rows.map((r, i) => ({ kind: r.kind, id: r.id, hash: r.hash, embedding: vectors[i] })),
      p_model: modelKey(cfg), p_staging: staging,
    }));
    const left = Number(rows[0].remaining) - rows.length;
    if (left <= 0) return 0;
    if (Date.now() > deadline) return left;
  }
}

serve(async (req, body) => {
  const db = userClient(req);
  const cfg = checkCfg(body.embedding, "embeddings");
  const key = modelKey(cfg);
  const deadline = Date.now() + TIME_BUDGET_MS;
  const status = must(await db.rpc("rag_status")) as Status;
  if (!status.enabled) throw new HttpError("Linking is off.", 409);

  switch (body.action) {
    // ───────────── embed what is missing, or rebuild everything with a new model ─────────────
    case "sync": {
      if (key !== status.selected_model) throw new HttpError("The embedding model on this phone does not match Settings.", 409);
      const rebuild = body.mode === "rebuild";
      if (rebuild && status.selected_model === status.active_model) throw new HttpError("Nothing to rebuild.", 409);
      if (!rebuild && status.selected_model !== status.active_model) {
        throw new HttpError("Embedding model changed — use Rebuild all or Switch back.", 409);
      }
      const remaining = await embedPending(db, cfg, rebuild, deadline);
      if (rebuild && remaining === 0) must(await db.rpc("rag_swap"));
      return { remaining, status: must(await db.rpc("rag_status")) };
    }

    // ───────────── place one message's new notes into matching Topics ─────────────
    case "link": {
      const sessionId = body.session_id as string, messageId = body.message_id as string;
      if (!sessionId || !messageId) throw new HttpError("session_id and message_id are required");
      if (status.selected_model !== status.active_model || key !== status.active_model) {
        return { linked: [], skipped: "Linking is paused until the index is rebuilt." };
      }
      const placement = checkCfg(body.placement, "placement");
      await embedPending(db, cfg, false, deadline);

      const { data: session } = await db.from("sessions").select("id, title").eq("id", sessionId).maybeSingle();
      if (!session) throw new HttpError("Session not found", 404);
      const bullets = must(await db.from("bullets")
        .select("id, content, position, section_id, sections!inner(id, title, session_id)")
        .eq("origin_message_id", messageId).eq("sections.session_id", sessionId).order("position")) as unknown as
        { id: string; content: string; section_id: string; sections: { title: string } }[];
      if (bullets.length === 0) return { linked: [] };

      // one group per section the message added notes to
      const groups = new Map<string, { title: string; ids: string[]; texts: string[] }>();
      for (const b of bullets) {
        const g = groups.get(b.section_id) ?? { title: b.sections.title, ids: [], texts: [] };
        g.ids.push(b.id); g.texts.push(b.content);
        groups.set(b.section_id, g);
      }
      const list = [...groups.values()];
      const vectors = await embed(cfg, list.map((g) => `${g.title}\n${g.texts.map((t) => `- ${t}`).join("\n")}`));

      const linked: { points: number; topic: string; section: string; is_new: boolean }[] = [];
      await Promise.all(list.map(async (g, i) => {
        const [secRows, topicRows] = await Promise.all([
          db.rpc("match_sections", { p_embedding: vectors[i], p_exclude_session: sessionId, p_limit: 5, p_placement: true }),
          db.rpc("match_topics", { p_embedding: vectors[i], p_exclude_session: sessionId, p_limit: 3 }),
        ]);
        const secs = (must(secRows) as { section_id: string; session_id: string; session_title: string; section_title: string; similarity: number; preview: string }[])
          .filter((r) => r.similarity >= MIN_SIMILARITY);
        const tops = (must(topicRows) as { session_id: string; session_title: string; similarity: number; preview: string }[])
          .filter((r) => r.similarity >= MIN_SIMILARITY);
        // No good candidates → the call is skipped and the notes stay where they are.
        if (secs.length === 0 && tops.length === 0) return;

        const sections: SectionCandidate[] = secs.map((r, n) => ({
          ref: `C${n + 1}`, section_id: r.section_id, session_id: r.session_id, topic: r.session_title, section: r.section_title, preview: r.preview,
        }));
        const topicMap = new Map<string, { title: string; preview: string }>();
        for (const t of tops) topicMap.set(t.session_id, { title: t.session_title, preview: t.preview });
        for (const s of secs) if (!topicMap.has(s.session_id)) topicMap.set(s.session_id, { title: s.session_title, preview: s.session_title });
        const topics: TopicCandidate[] = [...topicMap.entries()].map(([id, t], n) => ({
          ref: `T${n + 1}`, session_id: id, topic: t.title, preview: t.preview,
        }));

        const out = await generateJson(placement, buildPlacementRequest({
          fromSession: session.title, groupTitle: g.title, bullets: g.texts, sections, topics,
        }));
        const choice = resolvePlacement(out, sections, topics);
        if (choice.action === "ADD_TO_SECTION") {
          must(await db.rpc("link_bullets", { p_bullet_ids: g.ids, p_section_id: choice.section.section_id, p_topic_id: null, p_title: null }));
          linked.push({ points: g.ids.length, topic: choice.section.topic, section: choice.section.section, is_new: false });
        } else if (choice.action === "NEW_SECTION_IN_TOPIC") {
          must(await db.rpc("link_bullets", { p_bullet_ids: g.ids, p_section_id: null, p_topic_id: choice.topic.session_id, p_title: g.title }));
          linked.push({ points: g.ids.length, topic: choice.topic.topic, section: g.title, is_new: true });
        }
      }));

      if (linked.length > 0) {
        const summary = "Also added to " + linked.map((l) =>
          `**${l.topic} › ${l.section}**${l.is_new ? " (new section)" : ""} · ${l.points} point${l.points === 1 ? "" : "s"}`).join(", ") + ".";
        must(await db.from("messages").insert({ session_id: sessionId, role: "assistant", content: summary }).select("id").single());
        await embedPending(db, cfg, false, deadline); // target sections changed
      }
      return { linked };
    }

    // ───────────── the "meaning" half of hybrid search ─────────────
    case "search": {
      const query = String(body.query ?? "").trim();
      if (!query) return { results: [] };
      if (!status.ready || key !== status.active_model) return { results: [], skipped: "Index not ready" };
      const [vector] = await embed(cfg, [query]);
      const rows = must(await db.rpc("match_sections", { p_embedding: vector, p_exclude_session: null, p_limit: 8, p_placement: false })) as
        { similarity: number }[];
      return { results: rows.filter((r) => r.similarity >= MIN_SIMILARITY) };
    }

    default:
      throw new HttpError("Unknown action");
  }
});

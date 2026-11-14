// POST /functions/v1/generate-notes
//
// Headers: Authorization: Bearer <user's Supabase access token>  (row-level security applies)
// Body:
//   { "session_id": uuid,
//     "mode"?: "notes" | "reference" | "summary" | "figures",      // default "notes"
//     "text"?: string,
//     "images"?: [{ "mime": "image/jpeg", "data": "<base64>" }],   // max 10
//     "figures"?: [{ "key": "F1", "path": "<user>/<session>/<file>.jpg", "mime": "image/jpeg", "data": "<base64>" }],
//                  // pictures cut out of the pages, already in the "figures" bucket; notes mode only, max 10 (6 picked by hand; up to 10 found in a PDF part)
//     "local_date": "YYYY-MM-DD",                                   // the phone's date
//     "provider": { "kind": "openai"|"anthropic"|"gemini"|"compatible", "api_key": "...",
//                   "model": "...", "base_url"?: "..." } }
//
//   notes      short notes; links in it go to References and are cited as [n]
//   figures    only "figures" (with "page" and "label"): figures found in a PDF that you kept, placed
//              into the notes already made; max 10 per call
//   reference  links only → References; nothing is added to Notes
//   summary    your own summary or drawn diagram → a diagram in Overview; nothing is added to Notes
//
// The key is used for this one call and never stored. Photos go to the AI and are then
// dropped — only the results are saved.

import type { SupabaseClient } from "npm:@supabase/supabase-js@2.119.0";
import { HttpError, must, requireUser, serve, userClient } from "../_shared/http.ts";
import { checkCfg, generateJson, type ImageInput } from "../_shared/providers.ts";
import {
  buildNotesRequest, buildReferenceRequest, buildSummaryRequest, cleanMermaid, cleanMindmap, type ExistingSection, placeFigures,
  renderPreviousNotes, type SessionType, summarize, toApplyPayload,
} from "../_shared/notes.ts";
import { repairMermaid } from "../_shared/mermaid_repair.ts";
import { buildPlaceRequest, placePayload } from "../_shared/find_figures.ts";
import {
  citeRefs, extractUrls, type KnownRef, linksBlock, type ModelLink, refList, refsToSave, type SavedRef,
} from "../_shared/links.ts";

type Mode = "notes" | "reference" | "summary" | "figures";

serve(async (req, body) => {
  const sessionId = body.session_id as string | undefined;
  const mode = (["notes", "reference", "summary", "figures"].includes(String(body.mode)) ? body.mode : "notes") as Mode;
  const text = String(body.text ?? "");
  const images = (body.images ?? []) as ImageInput[];
  const localDate = String(body.local_date ?? "");
  if (!sessionId) throw new HttpError("session_id is required");
  if (!text.trim() && images.length === 0 && !((body.figures as unknown[] | undefined)?.length)) throw new HttpError("Send text or at least one photo");
  if (images.length > 10) throw new HttpError("Up to 10 photos per message");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(localDate)) throw new HttpError("local_date is required (YYYY-MM-DD)");
  const provider = checkCfg(body.provider, "notes");
  const db = userClient(req);
  const userId = await requireUser(req, db);
  type Figure = { key: string; path: string; mime: string; data: string; page?: number; label?: string };
  const figures = ((body.figures ?? []) as Figure[]).filter((f) =>
    /^F\d+$/i.test(String(f?.key)) && String(f?.path).startsWith(`${userId}/${sessionId}/`) && f?.data);
  if (figures.length > 10) throw new HttpError("Up to 10 figures per message");
  if (mode === "figures" && figures.length === 0) throw new HttpError("No figures to add");

  // 1. The session and its saved references
  const { data: session } = await db.from("sessions").select("id, title, type, is_active").eq("id", sessionId).maybeSingle();
  if (!session) throw new HttpError("Session not found", 404);
  if (!session.is_active) throw new HttpError("This session is inactive — mark it active to add to it.", 409);
  const type = session.type as SessionType;
  const known = (must(await db.from("session_references").select("id, number, url, title")
    .eq("session_id", sessionId).order("number", { ascending: false }).limit(80)) as KnownRef[]).reverse();
  const textUrls = extractUrls(text);

  const logMessage = async () => must(await db.from("messages")
    .insert({ session_id: sessionId, role: "user", content: text, image_count: images.length, mode })
    .select("id").single()) as { id: string };
  const reply = async (content: string) =>
    must(await db.from("messages").insert({ session_id: sessionId, role: "assistant", content, mode }).select("id").single());

  // ───── summary: your own summary or diagram → Overview ─────
  if (mode === "summary") {
    const out = await generateJson(provider, buildSummaryRequest({ sessionTitle: session.title, newText: text, images })) as
      { title?: string; mermaid?: string; note?: string };
    const raw = cleanMermaid(String(out?.mermaid ?? ""));
    const mermaid = /^\s*mindmap/i.test(raw) ? cleanMindmap(raw) : repairMermaid(raw);
    const msg = await logMessage();
    const saved = await saveRefs(db, sessionId, msg.id, textUrls, [], localDate);
    const title = String(out?.title ?? "").trim().slice(0, 120) || "Summary";
    must(await db.from("session_summaries").insert({
      session_id: sessionId, kind: "upload", title, mermaid, note: String(out?.note ?? "").trim().slice(0, 1200), origin_message_id: msg.id,
    }).select("id").single());
    const summary = `Saved your summary **${title}** to Overview. Nothing was added to Notes.` +
      (saved.some((r) => r.is_new) ? `\nAlso saved to References: ${refList(saved.filter((r) => r.is_new))}.` : "");
    await reply(summary);
    return { user_message_id: msg.id, summary, new_bullets: 0 };
  }

  // ───── reference: links only → References ─────
  if (mode === "reference") {
    const out = await generateJson(provider, buildReferenceRequest({
      sessionTitle: session.title, newText: text, images, links: linksBlock(known, textUrls),
    })) as { links?: ModelLink[] };
    const msg = await logMessage();
    const saved = await saveRefs(db, sessionId, msg.id, textUrls, out?.links ?? [], localDate);
    const fresh = saved.filter((r) => r.is_new), old = saved.filter((r) => !r.is_new);
    const summary = saved.length === 0
      ? "No links found in this message, so nothing was saved."
      : (fresh.length ? `Saved **${fresh.length === 1 ? "1 reference" : `${fresh.length} references`}**: ${refList(fresh)}.` : "") +
        (old.length ? `${fresh.length ? "\n" : ""}Already saved: ${refList(old)}.` : "") + "\nNothing was added to Notes.";
    await reply(summary);
    return { user_message_id: msg.id, summary, new_bullets: 0 };
  }

  // ───── notes ─────
  // 2. Previous notes of THIS session (context — old photos are never re-sent)
  const rows = must(await db.from("sections")
    .select("id, title, position, bullets!bullets_section_id_fkey(id, content, kind, level, position)").eq("session_id", sessionId).order("position")) as
    { id: string; title: string; bullets: { id: string; content: string; kind: string; level: number; position: number }[] }[];
  const sections: ExistingSection[] = rows.map((s) => ({
    id: s.id, title: s.title,
    bullets: [...(s.bullets ?? [])].sort((a, b) => a.position - b.position).map((b) => ({ id: b.id, content: b.content, kind: b.kind, level: b.level })),
  })).filter((s) => s.bullets.length > 0);
  const numbers = new Map(known.map((r) => [r.id, r.number]));
  const { text: previousNotes, refs } = renderPreviousNotes(sections, type === "topic" || mode === "figures", numbers);

  // ───── figures: ones you kept from a PDF, placed into the notes already made from it ─────
  if (mode === "figures") {
    const out = await generateJson(provider, buildPlaceRequest({
      sessionTitle: session.title, previousNotes, refs,
      figures: figures.map((f) => ({ key: f.key.toUpperCase(), page: Number(f.page) || undefined, label: String(f.label ?? "").slice(0, 120),
        image: { mime: f.mime || "image/jpeg", data: f.data } })),
    }));
    const msg = must(await db.from("messages")
      .insert({ session_id: sessionId, role: "user", content: text || "Figures", image_count: 0, mode: "notes" })
      .select("id").single()) as { id: string };
    const payload = placePayload(out, refs, figures.map((f) => ({ key: f.key, path: f.path, label: String(f.label ?? "") })));
    const result = must(await db.rpc("apply_notes", {
      p_session_id: sessionId, p_message_id: msg.id, p_payload: payload, p_today: localDate,
    })) as { new_figures: number; sections: { title: string; figures?: number }[] };
    const where = [...new Set(result.sections.filter((x) => (x.figures ?? 0) > 0).map((x) => `**${x.title}**`))];
    const n = result.new_figures;
    const summary = `Added ${n === 1 ? "1 figure" : `${n} figures`}${where.length ? ` to ${where.join(", ")}` : ""}.`;
    must(await db.from("messages").insert({ session_id: sessionId, role: "assistant", content: summary, mode: "notes" }).select("id").single());
    return { user_message_id: msg.id, summary, new_bullets: n };
  }

  // 3. One AI call
  const output = await generateJson(provider, buildNotesRequest({
    sessionType: type, sessionTitle: session.title, previousNotes, refs, newText: text, images,
    links: linksBlock(known, textUrls),
    figures: figures.map((f) => ({ key: f.key.toUpperCase(), image: { mime: f.mime || "image/jpeg", data: f.data } })),
  })) as { sections?: unknown[]; skipped_duplicates?: number; links?: ModelLink[] };

  // 4. Log the chat message, save its links, then save the notes (citing them) atomically
  const msg = await logMessage();
  const saved = await saveRefs(db, sessionId, msg.id, textUrls, output?.links ?? [], localDate);
  const ids = new Map<string, string>(known.map((r) => [`R${r.number}`, r.id]));
  for (const r of saved) ids.set(r.key, r.id);

  const payload = toApplyPayload(type, output as never, refs);
  for (const g of payload.sections) {
    for (const b of g.bullets) if (b.kind === "text" || b.kind === "table") b.text = citeRefs(b.text, ids, textUrls);
    g.bullets = g.bullets.filter((b) => b.text.replace(/\[\[ref:[0-9a-fA-F-]+\]\]/g, "").trim().length > 0);
  }
  payload.sections = placeFigures(payload.sections.filter((g) => g.bullets.length > 0) as never,
    figures.map((f) => ({ key: f.key, path: f.path }))) as never;
  const result = must(await db.rpc("apply_notes", {
    p_session_id: sessionId, p_message_id: msg.id, p_payload: payload, p_today: localDate,
  })) as { new_bullets: number; new_diagrams: number; new_tables: number; new_code: number; new_figures: number; new_sections: number; sections: never[] };

  const fresh = saved.filter((r) => r.is_new);
  const summary = summarize(result, Math.max(0, Number(output?.skipped_duplicates) || 0)) +
    (fresh.length ? `\nSaved to References: ${refList(fresh)}.` : "");
  await reply(summary);

  return { user_message_id: msg.id, summary, new_bullets: result.new_bullets, new_diagrams: result.new_diagrams, new_sections: result.new_sections };
});

async function saveRefs(db: SupabaseClient, sessionId: string, messageId: string, textUrls: string[], links: ModelLink[], today: string): Promise<SavedRef[]> {
  const toSave = refsToSave(textUrls, links);
  if (toSave.length === 0) return [];
  return must(await db.rpc("upsert_references", {
    p_session_id: sessionId, p_message_id: messageId, p_refs: toSave, p_today: today,
  })) as SavedRef[];
}

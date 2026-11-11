// POST /functions/v1/generate-notes
//
// Headers: Authorization: Bearer <user's Supabase access token>  (row-level security applies)
// Body:
//   { "session_id": uuid,
//     "text"?: string,
//     "images"?: [{ "mime": "image/jpeg", "data": "<base64>" }],   // max 10
//     "local_date": "YYYY-MM-DD",                                   // the phone's date
//     "provider": { "kind": "openai"|"anthropic"|"gemini"|"compatible", "api_key": "...",
//                   "model": "...", "base_url"?: "..." } }
//
// The key is used for this one call and never stored. Photos go to the AI and are then
// dropped — only the resulting notes are saved.

import { HttpError, must, requireUser, serve, userClient } from "../_shared/http.ts";
import { checkCfg, generateJson, type ImageInput } from "../_shared/providers.ts";
import {
  buildNotesRequest, type ExistingSection, renderPreviousNotes, type SessionType, summarize, toApplyPayload,
} from "../_shared/notes.ts";

serve(async (req, body) => {
  const sessionId = body.session_id as string | undefined;
  const text = String(body.text ?? "");
  const images = (body.images ?? []) as ImageInput[];
  const localDate = String(body.local_date ?? "");
  if (!sessionId) throw new HttpError("session_id is required");
  if (!text.trim() && images.length === 0) throw new HttpError("Send text or at least one photo");
  if (images.length > 10) throw new HttpError("Up to 10 photos per message");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(localDate)) throw new HttpError("local_date is required (YYYY-MM-DD)");
  const provider = checkCfg(body.provider, "notes");
  const db = userClient(req);
  await requireUser(req, db);

  // 1. The session
  const { data: session } = await db.from("sessions").select("id, title, type, is_active").eq("id", sessionId).maybeSingle();
  if (!session) throw new HttpError("Session not found", 404);
  if (!session.is_active) throw new HttpError("This session is inactive — mark it active to add notes.", 409);
  const type = session.type as SessionType;

  // 2. Previous notes of THIS session (context — old photos are never re-sent)
  const rows = must(await db.from("sections")
    .select("id, title, position, bullets!bullets_section_id_fkey(id, content, position)").eq("session_id", sessionId).order("position")) as
    { id: string; title: string; bullets: { id: string; content: string; position: number }[] }[];
  const sections: ExistingSection[] = rows.map((s) => ({
    id: s.id, title: s.title,
    bullets: [...(s.bullets ?? [])].sort((a, b) => a.position - b.position).map((b) => ({ id: b.id, content: b.content })),
  })).filter((s) => s.bullets.length > 0);
  const { text: previousNotes, refs } = renderPreviousNotes(sections, type === "topic");

  // 3. One AI call
  const output = await generateJson(provider, buildNotesRequest({
    sessionType: type, sessionTitle: session.title, previousNotes, refs, newText: text, images,
  })) as { sections?: unknown[]; skipped_duplicates?: number };

  // 4. Log the chat message (text + photo count only), then save the notes atomically
  const msg = must(await db.from("messages")
    .insert({ session_id: sessionId, role: "user", content: text, image_count: images.length })
    .select("id").single()) as { id: string };

  const payload = toApplyPayload(type, output as never, refs);
  const result = must(await db.rpc("apply_notes", {
    p_session_id: sessionId, p_message_id: msg.id, p_payload: payload, p_today: localDate,
  })) as { new_bullets: number; new_sections: number; sections: never[] };

  const summary = summarize(result, Math.max(0, Number(output?.skipped_duplicates) || 0));
  must(await db.from("messages").insert({ session_id: sessionId, role: "assistant", content: summary }).select("id").single());

  return { user_message_id: msg.id, summary, new_bullets: result.new_bullets, new_sections: result.new_sections };
});

// POST /functions/v1/make-overview — "Make a better overview": one AI call that turns a session's
// notes into a recall map (a mindmap of names only). Replaces the previous AI overview.
//
// Body: { "session_id": uuid, "provider": ProviderCfg }  →  { "id", "mermaid", "created_at" }

import { HttpError, must, requireUser, serve, userClient } from "../_shared/http.ts";
import { checkCfg, generateJson } from "../_shared/providers.ts";
import { buildOverviewRequest, cleanMindmap } from "../_shared/notes.ts";
import { stripRefs } from "../_shared/links.ts";

const MAX_CHARS = 14_000;

serve(async (req, body) => {
  const sessionId = body.session_id as string | undefined;
  if (!sessionId) throw new HttpError("session_id is required");
  const provider = checkCfg(body.provider, "notes");
  const db = userClient(req);
  await requireUser(req, db);

  const { data: session } = await db.from("sessions").select("id, title").eq("id", sessionId).maybeSingle();
  if (!session) throw new HttpError("Session not found", 404);
  const rows = must(await db.from("sections")
    .select("title, position, bullets!bullets_section_id_fkey(content, kind, position)").eq("session_id", sessionId).order("position")) as
    { title: string; bullets: { content: string; kind: string; position: number }[] }[];
  let notes = rows.filter((s) => s.bullets?.length).map((s) =>
    `## ${s.title}\n` + [...s.bullets].sort((a, b) => a.position - b.position)
      .filter((b) => b.kind === "text" || b.kind === "table").map((b) => `- ${stripRefs(b.content)}`).join("\n")).join("\n\n");
  if (!notes.trim()) throw new HttpError("This session has no notes yet.", 409);
  if (notes.length > MAX_CHARS) notes = notes.slice(0, MAX_CHARS) + "\n…";

  const out = await generateJson(provider, buildOverviewRequest({ sessionTitle: session.title, notes })) as { mermaid?: string };
  const mermaid = cleanMindmap(String(out?.mermaid ?? ""));
  if (!/^mindmap\n/.test(mermaid)) throw new HttpError("The model did not return a mindmap. Try again.", 502);

  must(await db.from("session_summaries").delete().eq("session_id", sessionId).eq("kind", "ai_overview").select("id"));
  return must(await db.from("session_summaries").insert({
    session_id: sessionId, kind: "ai_overview", title: "AI overview", mermaid,
  }).select("id, mermaid, created_at").single());
});

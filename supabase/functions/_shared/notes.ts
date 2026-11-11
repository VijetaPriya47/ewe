// Prompt + output schema for turning new study material into short notes.
// Pure TypeScript (no Deno APIs) so it can be unit-tested anywhere.

import type { ImageInput, JsonRequest, Part } from "./providers.ts";

export type SessionType = "topic" | "project";

export interface ExistingBullet { id: string; content: string }
export interface ExistingSection { id: string; title: string; bullets: ExistingBullet[] }

export interface RefMaps {
  sectionByRef: Map<string, string>; // "S1"   -> section uuid
  bulletByRef: Map<string, string>;  // "S1.3" -> bullet uuid
}

/** Render existing notes compactly. Topics get short refs (S1, S1.2) so the AI can
 *  point at a place; projects don't need them (notes are only appended). */
export function renderPreviousNotes(sections: ExistingSection[], withRefs: boolean): { text: string; refs: RefMaps } {
  const refs: RefMaps = { sectionByRef: new Map(), bulletByRef: new Map() };
  if (sections.length === 0) return { text: "(none yet — this is the first upload)", refs };

  const lines: string[] = [];
  sections.forEach((s, i) => {
    const sRef = `S${i + 1}`;
    refs.sectionByRef.set(sRef, s.id);
    lines.push(withRefs ? `## [${sRef}] ${s.title}` : `## ${s.title}`);
    s.bullets.forEach((b, j) => {
      const bRef = `${sRef}.${j + 1}`;
      refs.bulletByRef.set(bRef, b.id);
      lines.push(withRefs ? `- [${bRef}] ${b.content}` : `- ${b.content}`);
    });
  });
  return { text: lines.join("\n"), refs };
}

const COMMON_RULES = `
# YOUR JOB
- You make short revision notes from study material (book pages, handwritten notes, subtitles, text).
- You get two inputs, clearly labelled below:
  1. PREVIOUS NOTES — notes already saved for this session. CONTEXT ONLY.
  2. NEW CONTENT — what the user just added (images and/or text).
- Make notes ONLY for the NEW CONTENT.

# DO NOT
- Do NOT rewrite, repeat, summarise or correct the PREVIOUS NOTES.
- Do NOT add a point that already exists in PREVIOUS NOTES (skip duplicates and count them in "skipped_duplicates").
- Do NOT add anything that is not in the NEW CONTENT (no outside facts, no opinions).
- Do NOT describe the image itself ("this page shows…") — only its content.

# HOW TO WRITE EACH BULLET
- Short, concise, precise. Remove filler words.
- Keep EVERY distinct point, fact, definition, number, formula, example and step. Shorter, never lossy.
- One idea per bullet. Fragments are fine ("CDN = servers near users → lower latency").
- Keep technical terms, names and numbers exactly as written.
- Use "→" for cause/effect, "vs" for comparisons, "e.g." for examples.
- If text is unreadable, skip it — never guess.
- If the NEW CONTENT has nothing new, return "sections": [].
`.trim();

const TOPIC_RULES = `
# WHERE EACH NOTE GOES (this session is a TOPIC)
- Group your new bullets into groups. For each group pick "target":
  - An existing section ref (e.g. "S2") if the bullets belong under that section.
  - "NEW" if they form a new sub-topic → also give a short "title".
- For each bullet pick "insert_after":
  - An existing bullet ref in the target section (e.g. "S2.3") if the bullet expands or relates to that specific line → it will be placed right after it.
  - "END" to append at the end of the section (use "END" for all bullets in a "NEW" section).
- Bullets in one group keep their order.
`.trim();

const PROJECT_RULES = `
# WHERE NOTES GO (this session is a PROJECT)
- Notes are continuous: everything is appended after the previous notes.
- Group your new bullets under short section titles in reading order.
`.trim();

// Strict-schema providers cap the size of enums; past this we validate refs ourselves.
const MAX_ENUM = 200;

export function buildNotesRequest(opts: {
  sessionType: SessionType;
  sessionTitle: string;
  previousNotes: string;
  refs: RefMaps;
  newText: string;
  images: ImageInput[];
}): JsonRequest {
  const system = [COMMON_RULES, opts.sessionType === "topic" ? TOPIC_RULES : PROJECT_RULES].join("\n\n");

  const header =
`SESSION: "${opts.sessionTitle}" (${opts.sessionType})

=== PREVIOUS NOTES (context only — do NOT make notes from this) ===
${opts.previousNotes}
=== END OF PREVIOUS NOTES ===

=== NEW CONTENT (make notes ONLY from this) ===
${opts.images.length ? `[${opts.images.length} image(s) attached below]\n` : ""}${opts.newText.trim() || (opts.images.length ? "" : "(no text)")}`;

  const parts: Part[] = [{ text: header }];
  for (const image of opts.images) parts.push({ image });
  parts.push({ text: "=== END OF NEW CONTENT ===" });

  return { system, parts, schema: buildSchema(opts.sessionType, opts.refs), schemaName: "notes" };
}

/** Strict JSON schema. For topics the allowed targets/anchors are enums built from the
 *  real refs, so the model can only ever answer with a valid choice. */
export function buildSchema(sessionType: SessionType, refs: RefMaps): Record<string, unknown> {
  const bullet: Record<string, unknown> = {
    type: "object", additionalProperties: false,
    properties: { text: { type: "string" } }, required: ["text"],
  };
  const group: Record<string, unknown> = {
    type: "object", additionalProperties: false,
    properties: { title: { type: "string" }, bullets: { type: "array", items: bullet } },
    required: ["title", "bullets"],
  };

  if (sessionType === "topic") {
    const bRefs = [...refs.bulletByRef.keys()], sRefs = [...refs.sectionByRef.keys()];
    (bullet.properties as Record<string, unknown>).insert_after =
      bRefs.length <= MAX_ENUM ? { type: "string", enum: ["END", ...bRefs] } : { type: "string" };
    bullet.required = ["text", "insert_after"];
    (group.properties as Record<string, unknown>).target =
      sRefs.length <= MAX_ENUM ? { type: "string", enum: ["NEW", ...sRefs] } : { type: "string" };
    group.required = ["target", "title", "bullets"];
  }

  return {
    type: "object", additionalProperties: false,
    properties: { sections: { type: "array", items: group }, skipped_duplicates: { type: "integer" } },
    required: ["sections", "skipped_duplicates"],
  };
}

interface ModelBullet { text?: string; insert_after?: string }
interface ModelGroup { target?: string; title?: string; bullets?: ModelBullet[] }

/** Convert the model's ref-based answer into the payload apply_notes() expects. */
export function toApplyPayload(sessionType: SessionType, output: { sections?: ModelGroup[] }, refs: RefMaps) {
  return {
    sections: (output?.sections ?? []).map((g) => {
      const sectionId = sessionType === "topic" ? refs.sectionByRef.get(g.target ?? "") ?? null : null;
      return {
        section_id: sectionId,
        title: String(g.title ?? ""),
        bullets: (g.bullets ?? [])
          .filter((b) => typeof b?.text === "string" && b.text.trim().length > 0)
          .map((b) => {
            // only honour anchors that live in the chosen section
            const inSection = sectionId && b.insert_after?.startsWith(`${g.target}.`);
            const anchor = inSection ? refs.bulletByRef.get(b.insert_after!) ?? null : null;
            return { text: b.text!.trim(), after_bullet_id: anchor };
          }),
      };
    }).filter((s) => s.bullets.length > 0),
  };
}

interface AppliedSection { title: string; is_new: boolean; added: number; inline: number }

const plural = (n: number, w: string) => `${n} ${w}${n === 1 ? "" : "s"}`;

/** The short chat reply. **bold** is rendered by the app. */
export function summarize(result: { new_bullets: number; sections: AppliedSection[] }, skipped: number): string {
  const skippedLine = skipped > 0 ? `\n${plural(skipped, "point")} skipped: already in your notes.` : "";
  if (result.new_bullets === 0) return "Nothing new here — your notes already cover this.";

  const secs = result.sections;
  const allNew = secs.every((s) => s.is_new);
  let line: string;
  if (allNew) {
    line = `Added **${plural(result.new_bullets, "point")}** in ${secs.length === 1 ? "a new section" : `${secs.length} new sections`}: ` +
      secs.map((s) => `**${s.title}**`).join(", ") + ".";
  } else {
    const bits = secs.map((s) => {
      if (s.is_new) return `${s.added} in a new section **${s.title}**`;
      if (s.inline === s.added) return `${s.added} inline in **${s.title}**`;
      if (s.inline === 0) return `${s.added} in **${s.title}**`;
      return `${s.added} in **${s.title}** (${s.inline} inline)`;
    });
    line = `Added **${plural(result.new_bullets, "point")}**: ${bits.join(", ")}.`;
  }
  return line + skippedLine;
}

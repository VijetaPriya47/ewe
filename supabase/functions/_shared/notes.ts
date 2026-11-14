// Prompt + output schema for turning new study material into short notes.
// Pure TypeScript (no Deno APIs) so it can be unit-tested anywhere.

import type { ImageInput, JsonRequest, Part } from "./providers.ts";

export type SessionType = "topic" | "project";

export interface ExistingBullet { id: string; content: string; kind?: string }
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
      const text = b.kind === "mermaid" ? `(diagram already drawn: ${diagramSummary(b.content)})`
        : b.kind === "code" ? `(code block: ${diagramSummary(b.content)})`
        : b.kind === "table" ? `(table: ${diagramSummary(b.content.replace(/\n/g, " ; "))})`
        : b.content;
      lines.push(withRefs ? `- [${bRef}] ${text}` : `- ${text}`);
    });
  });
  return { text: lines.join("\n"), refs };
}

/** One line describing an existing diagram, so the model knows it exists without re-reading all of it. */
function diagramSummary(code: string): string {
  const flat = code.replace(/\s+/g, " ").trim();
  return flat.length > 120 ? flat.slice(0, 117) + "…" : flat;
}

/** Models sometimes wrap code in fences or add a language tag; keep only the Mermaid code. */
export function cleanMermaid(text: string): string {
  return text.trim().replace(/^```(?:mermaid)?\s*\n?/i, "").replace(/\n?```\s*$/, "").trim();
}

/** Code without surrounding fences, keeping its indentation. */
export function cleanCode(text: string): string {
  return text.replace(/^\s*```[\w+#.-]*[ \t]*\n?/, "").replace(/\n?```\s*$/, "").replace(/^\s*\n/, "").replace(/\s+$/, "");
}

/** A Markdown table needs at least a header row and one more row of pipes; otherwise it is plain text. */
export function isTable(text: string): boolean {
  return text.trim().split("\n").filter((l) => l.trim().startsWith("|") || l.split("|").length > 2).length >= 2;
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
- Every ordinary bullet has "kind": "text" and "lang": "".

# CODE AND TABLES
- Code, commands, queries or config in the NEW CONTENT: keep them as ONE bullet with "kind": "code",
  "text" = the code exactly as written (keep indentation and line breaks, no \`\`\` fences),
  "lang" = its language in lowercase (e.g. "python", "sql", "bash"), or "" if unknown. Never paraphrase code.
- A table in the NEW CONTENT, or several items compared on the same attributes: ONE bullet with
  "kind": "table", "text" = a Markdown table (header row, then "| --- |" row, then one row per item).
  Short cells. A table may hold those points instead of repeating them as text bullets.
- "lang" is "" for every bullet that is not code.

# IMPORTANT POINTS
- Set "important": true on the few bullets that matter most: key definitions, core rules, numbers or
  facts to remember, likely exam points. About 1 in 5 bullets at most; all others false.
- Set a group's "important": true only when the whole sub-topic is central to the material.

# DIAGRAMS (optional — most uploads need none)
- Add a diagram only when the NEW CONTENT shows a figure, or describes a process, flow, sequence,
  hierarchy, state change or relationships that are much clearer as a picture.
- A diagram is one extra bullet with "kind": "mermaid" and "text" = Mermaid code only (no \`\`\` fences).
- Put it in the group it illustrates, right after the text bullets it summarises.
- Use one of: flowchart TD, sequenceDiagram, stateDiagram-v2, classDiagram, erDiagram, mindmap.
- Keep it small: at most 12 nodes, short labels. Wrap labels with symbols in quotes: A["Cache (edge)"].
- At most 2 diagrams per upload. Never repeat a diagram that already exists in PREVIOUS NOTES.
- A diagram never replaces text: every point must still be in a text bullet.
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
    properties: {
      kind: { type: "string", enum: ["text", "mermaid", "code", "table"] },
      text: { type: "string" },
      lang: { type: "string" },
      important: { type: "boolean" },
    },
    required: ["kind", "text", "lang", "important"],
  };
  const group: Record<string, unknown> = {
    type: "object", additionalProperties: false,
    properties: { title: { type: "string" }, important: { type: "boolean" }, bullets: { type: "array", items: bullet } },
    required: ["title", "important", "bullets"],
  };

  if (sessionType === "topic") {
    const bRefs = [...refs.bulletByRef.keys()], sRefs = [...refs.sectionByRef.keys()];
    (bullet.properties as Record<string, unknown>).insert_after =
      bRefs.length <= MAX_ENUM ? { type: "string", enum: ["END", ...bRefs] } : { type: "string" };
    bullet.required = ["kind", "text", "lang", "important", "insert_after"];
    (group.properties as Record<string, unknown>).target =
      sRefs.length <= MAX_ENUM ? { type: "string", enum: ["NEW", ...sRefs] } : { type: "string" };
    group.required = ["target", "title", "important", "bullets"];
  }

  return {
    type: "object", additionalProperties: false,
    properties: { sections: { type: "array", items: group }, skipped_duplicates: { type: "integer" } },
    required: ["sections", "skipped_duplicates"],
  };
}

interface ModelBullet { kind?: string; text?: string; lang?: string; important?: boolean; insert_after?: string }
interface ModelGroup { target?: string; title?: string; important?: boolean; bullets?: ModelBullet[] }

type Kind = "text" | "mermaid" | "code" | "table";

/** The kind and the cleaned text of one model bullet; an unusable table becomes plain text. */
function normalise(b: ModelBullet): { kind: Kind; text: string } {
  const raw = String(b.text ?? "");
  switch (b.kind) {
    case "mermaid": return { kind: "mermaid", text: cleanMermaid(raw) };
    case "code": return { kind: "code", text: cleanCode(raw) };
    case "table": return isTable(raw) ? { kind: "table", text: raw.trim() } : { kind: "text", text: raw.trim() };
    default: return { kind: "text", text: raw.trim() };
  }
}

/** Convert the model's ref-based answer into the payload apply_notes() expects. */
export function toApplyPayload(sessionType: SessionType, output: { sections?: ModelGroup[] }, refs: RefMaps) {
  return {
    sections: (output?.sections ?? []).map((g) => {
      const sectionId = sessionType === "topic" ? refs.sectionByRef.get(g.target ?? "") ?? null : null;
      return {
        section_id: sectionId,
        title: String(g.title ?? ""),
        important: g.important === true,
        bullets: (g.bullets ?? [])
          .filter((b) => typeof b?.text === "string")
          .map((b) => {
            // only honour anchors that live in the chosen section
            const inSection = sectionId && b.insert_after?.startsWith(`${g.target}.`);
            const anchor = inSection ? refs.bulletByRef.get(b.insert_after!) ?? null : null;
            const { kind, text } = normalise(b);
            return {
              kind, text, after_bullet_id: anchor, important: b.important === true,
              ...(kind === "code" ? { lang: String(b.lang ?? "").trim().toLowerCase() } : {}),
            };
          })
          .filter((b) => b.text.trim().length > 0),
      };
    }).filter((s) => s.bullets.length > 0),
  };
}

interface AppliedSection { title: string; is_new: boolean; added: number; inline: number; diagrams?: number; tables?: number; code?: number }

const plural = (n: number, w: string) => `${n} ${w}${n === 1 ? "" : "s"}`;

/** The short chat reply. **bold** is rendered by the app. Diagrams, tables and code are counted apart from points. */
export function summarize(
  result: { new_bullets: number; new_diagrams?: number; new_tables?: number; new_code?: number; sections: AppliedSection[] },
  skipped: number,
): string {
  const skippedLine = skipped > 0 ? `\n${plural(skipped, "point")} skipped: already in your notes.` : "";
  if (result.new_bullets === 0) return "Nothing new here — your notes already cover this.";
  const extras = (s: { diagrams?: number; tables?: number; code?: number }) =>
    [[s.diagrams ?? 0, "diagram"], [s.tables ?? 0, "table"], [s.code ?? 0, "code block"]] as [number, string][];
  const extraTotal = (s: { diagrams?: number; tables?: number; code?: number }) => extras(s).reduce((n, [k]) => n + k, 0);

  const total = { diagrams: result.new_diagrams, tables: result.new_tables, code: result.new_code };
  const points = result.new_bullets - extraTotal(total);
  const parts = [points > 0 ? `**${plural(points, "point")}**` : "",
    ...extras(total).filter(([n]) => n > 0).map(([n, w]) => `**${plural(n, w)}**`)].filter(Boolean);
  const what = parts.length > 1 ? `${parts.slice(0, -1).join(", ")} and ${parts[parts.length - 1]}` : parts[0];

  const secs = result.sections;
  const allNew = secs.every((s) => s.is_new);
  let line: string;
  if (allNew) {
    line = `Added ${what} in ${secs.length === 1 ? "a new section" : `${secs.length} new sections`}: ` +
      secs.map((s) => `**${s.title}**`).join(", ") + ".";
  } else {
    const bits = secs.map((s) => {
      const n = s.added - extraTotal(s);
      const d = extras(s).filter(([k]) => k > 0).map(([k, w]) => ` + ${plural(k, w)}`).join("");
      if (s.is_new) return `${n} in a new section **${s.title}**${d}`;
      if (n > 0 && s.inline === n) return `${n} inline in **${s.title}**${d}`;
      if (s.inline === 0 || n === 0) return `${n} in **${s.title}**${d}`;
      return `${n} in **${s.title}** (${s.inline} inline)${d}`;
    });
    line = `Added ${what}: ${bits.join(", ")}.`;
  }
  return line + skippedLine;
}

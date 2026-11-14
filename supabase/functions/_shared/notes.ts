// Prompt + output schema for turning new study material into short notes.
// Pure TypeScript (no Deno APIs) so it can be unit-tested anywhere.

import type { ImageInput, JsonRequest, Part } from "./providers.ts";
import { repairMermaid } from "./mermaid_repair.ts";
import { LINK_ITEM_SCHEMA } from "./links.ts";

export type SessionType = "topic" | "project";

export interface ExistingBullet { id: string; content: string; kind?: string; level?: number }
export interface ExistingSection { id: string; title: string; bullets: ExistingBullet[] }

export interface RefMaps {
  sectionByRef: Map<string, string>; // "S1"   -> section uuid
  bulletByRef: Map<string, string>;  // "S1.3" -> bullet uuid
}

/** Render existing notes compactly. Topics get short refs (S1, S1.2) so the AI can
 *  point at a place; projects don't need them (notes are only appended). */
export function renderPreviousNotes(sections: ExistingSection[], withRefs: boolean, refNumbers: Map<string, number> = new Map()): { text: string; refs: RefMaps } {
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
        : b.content.replace(/\[\[ref:([0-9a-fA-F-]+)\]\]/g, (_m: string, id: string) => refNumbers.has(id) ? `[R${refNumbers.get(id)}]` : "");
      const indent = "  ".repeat(Math.min(Math.max(b.level ?? 0, 0), 2));  // sub-points indented, as in the app
      lines.push(withRefs ? `${indent}- [${bRef}] ${text}` : `${indent}- ${text}`);
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

# FIGURES
- FIGURES (after the NEW CONTENT) are pictures the user cut out of the pages: F1, F2, …
- Put each figure in the notes exactly once, as a bullet with "kind": "figure", "text": its key
  (e.g. "F1") and a "caption": one short line saying what it shows. Place it in the group it belongs
  to, right after the bullets it illustrates. Never put a figure key in any other bullet.
- "caption" is "" for every bullet that is not a figure or a diagram.

# IMPORTANT POINTS
- Set "important": true on the few bullets that matter most: key definitions, core rules, numbers or
  facts to remember, likely exam points. About 1 in 5 bullets at most; all others false.
- Set a group's "important": true only when the whole sub-topic is central to the material.

# NESTED POINTS
- "level" nests a bullet under the bullet right before it: 0 = a main point, 1 = a detail, example,
  step or part of the level-0 point above it, 2 = a detail of the level-1 point above it.
- A bullet's level is at most one more than the bullet before it; the first bullet of a group is 0
  unless "insert_after" puts it under an existing point (PREVIOUS NOTES show sub-points indented).
- Nest only where the material has real structure (parts of a definition, steps of a process,
  examples of a rule, pros/cons). Most bullets stay at level 0. Never nest just to look tidy.
- Only text bullets nest: diagrams, code, tables and figures are always level 0.

# LINKS
- KNOWN LINKS lists links you may cite: [R#] are saved already, [L#] appear in the NEW CONTENT text.
- When a bullet's point comes from a link or mentions it, end that bullet with its marker, e.g. "… [L1]".
  Several are fine: "… [R2][L1]". Never write a URL inside a bullet: use the marker.
- Links printed in the images (and not in KNOWN LINKS): add each to "links" with key "P1", "P2", …
  and the URL exactly as printed; cite it with its marker the same way.
- In "links", give every [L#] and [P#] link a short "title" (at most 8 words) and a one-line
  "description" of what it is about. Leave [R#] links out. "url" is "" for [L#] links.

# DIAGRAMS (optional — most uploads need none)
- Add a diagram only when the NEW CONTENT shows a figure, or describes a process, flow, sequence,
  hierarchy, state change or relationships that are much clearer as a picture.
- A diagram is one extra bullet with "kind": "mermaid" and "text" = Mermaid code only (no \`\`\` fences).
- Put it in the group it illustrates, right after the text bullets it summarises.
- Use one of: flowchart TD, sequenceDiagram, stateDiagram-v2, classDiagram, erDiagram, mindmap.
- ONE STATEMENT PER LINE: put a real line break (\\n in the JSON string) after the first line and after
  every arrow statement. Never write the whole diagram on one line. Example "text":
  "flowchart TD\\n  A[\\"Client\\"] --> B[\\"Edge\\"]\\n  B -->|\\"miss\\"| C[\\"Origin\\"]"
- Put every node label and edge label in double quotes: A["Fetch IDs (parallel)"], A -->|"miss"| B.
- Keep it small: at most 12 nodes, short labels.
- At most 2 diagrams per upload. Never repeat a diagram that already exists in PREVIOUS NOTES.
- Give every diagram a "caption": one short line saying what it shows (e.g. "Request path: user → edge → origin").
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
  links?: string;
  figures?: { key: string; image: ImageInput }[];
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
  parts.push({ text: "=== END OF NEW CONTENT ===\n\n" + (opts.links ?? "=== KNOWN LINKS ===\n(none)\n=== END OF KNOWN LINKS ===") });
  if (opts.figures?.length) {
    parts.push({ text: "\n=== FIGURES (cut out of the pages above; place each one once) ===" });
    for (const f of opts.figures) { parts.push({ text: `[${f.key}]` }); parts.push({ image: f.image }); }
    parts.push({ text: "=== END OF FIGURES ===" });
  }

  return { system, parts, schema: buildSchema(opts.sessionType, opts.refs), schemaName: "notes" };
}

/** Strict JSON schema. For topics the allowed targets/anchors are enums built from the
 *  real refs, so the model can only ever answer with a valid choice. */
export function buildSchema(sessionType: SessionType, refs: RefMaps): Record<string, unknown> {
  const bullet: Record<string, unknown> = {
    type: "object", additionalProperties: false,
    properties: {
      kind: { type: "string", enum: ["text", "mermaid", "code", "table", "figure"] },
      text: { type: "string" },
      lang: { type: "string" },
      caption: { type: "string" },
      important: { type: "boolean" },
      level: { type: "integer" },
    },
    required: ["kind", "text", "lang", "caption", "important", "level"],
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
    bullet.required = ["kind", "text", "lang", "caption", "important", "level", "insert_after"];
    (group.properties as Record<string, unknown>).target =
      sRefs.length <= MAX_ENUM ? { type: "string", enum: ["NEW", ...sRefs] } : { type: "string" };
    group.required = ["target", "title", "important", "bullets"];
  }

  return {
    type: "object", additionalProperties: false,
    properties: {
      sections: { type: "array", items: group },
      skipped_duplicates: { type: "integer" },
      links: { type: "array", items: LINK_ITEM_SCHEMA },
    },
    required: ["sections", "skipped_duplicates", "links"],
  };
}

interface ModelBullet { kind?: string; text?: string; lang?: string; caption?: string; important?: boolean; level?: number; insert_after?: string }
interface ModelGroup { target?: string; title?: string; important?: boolean; bullets?: ModelBullet[] }

type Kind = "text" | "mermaid" | "code" | "table" | "figure";

/** The kind and the cleaned text of one model bullet; an unusable table becomes plain text. */
function normalise(b: ModelBullet): { kind: Kind; text: string } {
  const raw = String(b.text ?? "");
  switch (b.kind) {
    case "mermaid": return { kind: "mermaid", text: repairMermaid(cleanMermaid(raw)) };
    case "code": return { kind: "code", text: cleanCode(raw) };
    case "table": return isTable(raw) ? { kind: "table", text: raw.trim() } : { kind: "text", text: raw.trim() };
    case "figure": return { kind: "figure", text: raw.replace(/[\[\]\s]/g, "").toUpperCase() };
    default: return { kind: "text", text: raw.trim() };
  }
}

/**
 * Levels a model can't get wrong: whole numbers 0–2, only on text, and at most one deeper than the
 * bullet before. The first bullet may start nested only when it is placed under an existing point.
 */
export function nestLevels<T extends { kind: string; level?: number; after_bullet_id?: string | null }>(bullets: T[]): (T & { level?: number })[] {
  let prev = -1;
  return bullets.map((b, i) => {
    const raw = Number.isFinite(b.level) ? Math.trunc(b.level as number) : 0;
    const ceiling = i === 0 ? (b.after_bullet_id ? 2 : 0) : (prev < 0 ? 0 : prev + 1);
    const level = b.kind === "text" ? Math.min(Math.max(raw, 0), 2, ceiling) : 0;
    prev = level;
    return { ...b, level };
  });
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
        bullets: nestLevels((g.bullets ?? [])
          .filter((b) => typeof b?.text === "string")
          .map((b) => {
            // only honour anchors that live in the chosen section
            const inSection = sectionId && b.insert_after?.startsWith(`${g.target}.`);
            const anchor = inSection ? refs.bulletByRef.get(b.insert_after!) ?? null : null;
            const { kind, text } = normalise(b);
            return {
              kind, text, after_bullet_id: anchor, important: b.important === true,
              ...(b.level !== undefined ? { level: Number(b.level) as number | undefined } : {}),
              ...(kind === "code" ? { lang: String(b.lang ?? "").trim().toLowerCase() } : {}),
              ...(kind === "figure" || kind === "mermaid" ? { caption: String(b.caption ?? "").trim().slice(0, 200) } : {}),
            };
          })
          .filter((b) => b.text.trim().length > 0)),
      };
    }).filter((s) => s.bullets.length > 0),
  };
}

interface AppliedSection { title: string; is_new: boolean; added: number; inline: number; diagrams?: number; tables?: number; code?: number; figures?: number }

const plural = (n: number, w: string) => `${n} ${w}${n === 1 ? "" : "s"}`;

/** The short chat reply. **bold** is rendered by the app. Diagrams, tables and code are counted apart from points. */
export function summarize(
  result: { new_bullets: number; new_diagrams?: number; new_tables?: number; new_code?: number; new_figures?: number; sections: AppliedSection[] },
  skipped: number,
): string {
  const skippedLine = skipped > 0 ? `\n${plural(skipped, "point")} skipped: already in your notes.` : "";
  if (result.new_bullets === 0) return "Nothing new here — your notes already cover this.";
  type Extras = { diagrams?: number; tables?: number; code?: number; figures?: number };
  const extras = (s: Extras) =>
    [[s.diagrams ?? 0, "diagram"], [s.tables ?? 0, "table"], [s.code ?? 0, "code block"], [s.figures ?? 0, "figure"]] as [number, string][];
  const extraTotal = (s: Extras) => extras(s).reduce((n, [k]) => n + k, 0);

  const total = { diagrams: result.new_diagrams, tables: result.new_tables, code: result.new_code, figures: result.new_figures };
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

// ───────────────────────── Reference mode: save links, make no notes ─────────────────────────
const REFERENCE_RULES = `
# YOUR JOB
- The user is saving REFERENCE LINKS for a study session. Do NOT make notes.
- List every link in the NEW CONTENT in "links":
  - links from the text: key "L1", "L2", … as in KNOWN LINKS, with "url": "".
  - links printed in the images: key "P1", "P2", … with the URL exactly as printed.
- For each, a short "title" (at most 8 words) and a one-line "description" of what it is about,
  taken from what the NEW CONTENT says about it, or what the link plainly is.
- Do not list [R#] links (already saved). Do not invent links.
`.trim();

export function buildReferenceRequest(opts: { sessionTitle: string; newText: string; images: ImageInput[]; links: string }): JsonRequest {
  const parts: Part[] = [{ text: `SESSION: "${opts.sessionTitle}"\n\n=== NEW CONTENT ===\n${opts.newText.trim() || "(no text)"}` }];
  for (const image of opts.images) parts.push({ image });
  parts.push({ text: "=== END OF NEW CONTENT ===\n\n" + opts.links });
  return {
    system: REFERENCE_RULES, parts, schemaName: "references",
    schema: { type: "object", additionalProperties: false, properties: { links: { type: "array", items: LINK_ITEM_SCHEMA } }, required: ["links"] },
  };
}

// ───────────────────────── Summary mode: your own summary → Overview ─────────────────────────
const SUMMARY_RULES = `
# YOUR JOB
- The user is adding their OWN SUMMARY of a study session: text, or a photo of a summary or of a
  diagram they drew. Do NOT make notes.
- Turn it into ONE Mermaid diagram that shows its structure:
  - a drawn diagram: copy its boxes and arrows as a flowchart;
  - a process or steps: a flowchart;
  - a list or outline of topics: a mindmap.
- Use the user's own words for labels, kept short. Do not add anything that is not in it.
- At most 25 nodes. ONE STATEMENT PER LINE (real line breaks, \\n in the JSON string).
  Flowchart labels in double quotes: A["Fetch IDs (parallel)"], A -->|"miss"| B.
  Mindmap: "mindmap", then "  root((Title))", children indented 2 more spaces than their parent,
  plain words only (no brackets, parentheses or quotes in names).
- "title": a few words naming the summary. "note": its key text in at most 80 words, or "" if it was only a diagram.
`.trim();

export function buildSummaryRequest(opts: { sessionTitle: string; newText: string; images: ImageInput[] }): JsonRequest {
  const parts: Part[] = [{ text: `SESSION: "${opts.sessionTitle}"\n\n=== THE USER'S SUMMARY ===\n${opts.newText.trim() || "(see the images)"}` }];
  for (const image of opts.images) parts.push({ image });
  parts.push({ text: "=== END ===" });
  return {
    system: SUMMARY_RULES, parts, schemaName: "summary",
    schema: {
      type: "object", additionalProperties: false,
      properties: { title: { type: "string" }, mermaid: { type: "string" }, note: { type: "string" } },
      required: ["title", "mermaid", "note"],
    },
  };
}

// ───────────────────────── AI overview: names only, for recall ─────────────────────────
const OVERVIEW_RULES = `
# YOUR JOB
- Make a RECALL MAP of a study session: a Mermaid mindmap with ONLY names — the session, its
  sections, and under each section 2 to 4 key sub-topic names (2 to 4 words each).
- No definitions, numbers, explanations or examples: the user will try to recall those.
- Format exactly:
  mindmap
    root((Session title))
      Section name
        Sub-topic
- One name per line, children indented 2 more spaces than their parent. Plain words only:
  no brackets, parentheses, quotes or colons in names.
`.trim();

export function buildOverviewRequest(opts: { sessionTitle: string; notes: string }): JsonRequest {
  return {
    system: OVERVIEW_RULES,
    parts: [{ text: `SESSION: "${opts.sessionTitle}"\n\n=== NOTES ===\n${opts.notes}\n=== END ===` }],
    schemaName: "overview",
    schema: { type: "object", additionalProperties: false, properties: { mermaid: { type: "string" } }, required: ["mermaid"] },
  };
}

/** Mindmap names cannot hold brackets or quotes (they mean shapes); strip them, keep the root's (( )). */
export function cleanMindmap(code: string): string {
  const lines = cleanMermaid(code).split("\n");
  if (!/^\s*mindmap\s*$/i.test(lines[0] ?? "")) return code;
  return lines.map((line, i) => {
    if (i === 0) return "mindmap";
    const m = line.match(/^(\s*)(root\(\()(.*)(\)\))\s*$/);
    if (m) return m[1] + m[2] + m[3].replace(/[()\[\]{}"]/g, "").trim() + m[4];
    const indent = line.match(/^\s*/)![0];
    return indent + line.trim().replace(/[()\[\]{}"`:]/g, "").replace(/\s{2,}/g, " ");
  }).filter((l) => l.trim().length > 0).join("\n");
}

// ───────────────────────── figures: model keys → storage paths ─────────────────────────
type PayloadBullet = { kind: string; text: string; after_bullet_id: string | null; important: boolean; caption?: string; lang?: string; level?: number };
type PayloadGroup = { section_id: string | null; title: string; important: boolean; bullets: PayloadBullet[] };

/**
 * Figure bullets name a key (F1…); swap in the stored picture's path. Unknown or repeated keys are
 * dropped, and a figure the model did not place is added to the end anyway, so none is lost.
 */
export function placeFigures(groups: PayloadGroup[], figures: { key: string; path: string }[]): PayloadGroup[] {
  const paths = new Map(figures.map((f) => [f.key.toUpperCase(), f.path]));
  const used = new Set<string>();
  for (const g of groups) {
    g.bullets = g.bullets.flatMap((b) => {
      if (b.kind !== "figure") return [b];
      const path = paths.get(b.text);
      if (!path || used.has(b.text)) return [];
      used.add(b.text);
      return [{ ...b, text: path }];
    });
  }
  const left = figures.filter((f) => !used.has(f.key.toUpperCase()));
  if (left.length) {
    const extra = left.map((f) => ({ kind: "figure", text: f.path, after_bullet_id: null, important: false, caption: "", level: 0 }));
    const last = groups[groups.length - 1];
    if (last) last.bullets.push(...extra);
    else groups.push({ section_id: null, title: "Figures", important: false, bullets: extra });
  }
  return groups.filter((g) => g.bullets.length > 0);
}

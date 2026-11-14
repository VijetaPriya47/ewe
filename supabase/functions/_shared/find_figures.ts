// Finding figures on PDF pages: the notes model says where each diagram is, the phone cuts it out.

import type { ImageInput, JsonRequest } from "./providers.ts";
import type { RefMaps } from "./notes.ts";

/** One figure on one page. Box edges are 0–1000 across the page image (0,0 = top left). */
export interface FoundFigure { page: number; top: number; left: number; bottom: number; right: number; label: string }

/** At most this many per call (one call = one part of up to 10 pages, the same as a notes message). */
export const MAX_FIGURES_PER_CALL = 10;

const SYSTEM = `
# YOUR JOB
- You get pages of a document as images, numbered PAGE 1, PAGE 2, …
- Find every FIGURE that carries information: diagrams, flowcharts, charts and graphs, drawings,
  schematics, maps, screenshots, photos that show the subject.
- For each, give its page number and a tight box around it.

# NOT A FIGURE (skip these)
- Plain text, headings, bullet lists, equations, code.
- Tables made of text (they become notes, not pictures).
- Logos, icons, page decorations, headers and footers, page numbers, background shapes.

# THE BOX
- Edges as whole numbers from 0 to 1000, measured across the page image: "top" and "bottom"
  from the top edge, "left" and "right" from the left edge. 0,0 is the top-left corner.
- Include the figure's own labels, axes, legend and arrows. Leave out its caption line
  ("Figure 3: …") and the body text around it.
- Several parts that form one figure (e.g. a/b panels) are one box. Separate figures are separate boxes.
- "label": a few words saying what it shows (e.g. "TCP handshake sequence").
- If a page has no figures, give none for it. If no page has any, return "figures": [].
`.trim();

const SCHEMA = {
  type: "object", additionalProperties: false,
  properties: {
    figures: {
      type: "array",
      items: {
        type: "object", additionalProperties: false,
        properties: {
          page: { type: "integer" }, top: { type: "integer" }, left: { type: "integer" },
          bottom: { type: "integer" }, right: { type: "integer" }, label: { type: "string" },
        },
        required: ["page", "top", "left", "bottom", "right", "label"],
      },
    },
  },
  required: ["figures"],
};

export function buildFindRequest(pages: ImageInput[]): JsonRequest {
  return {
    system: SYSTEM,
    parts: pages.flatMap((p, i) => [{ text: `PAGE ${i + 1}` }, { image: p }]),
    schema: SCHEMA,
    schemaName: "found_figures",
  };
}

/**
 * Boxes the phone can trust: on a real page, edges in order and inside 0–1000, not a sliver, not the
 * whole page (that is a scan, not a figure), no near-duplicates. In page order, capped.
 */
export function cleanFound(output: unknown, pageCount: number): FoundFigure[] {
  const raw = ((output as { figures?: unknown[] })?.figures ?? []) as Record<string, unknown>[];
  const clamp = (v: unknown) => Math.min(Math.max(Math.round(Number(v)), 0), 1000);
  const kept: FoundFigure[] = [];
  for (const f of Array.isArray(raw) ? raw : []) {
    const page = Math.round(Number(f?.page));
    if (!Number.isFinite(page) || page < 1 || page > pageCount) continue;
    const vals = [f.top, f.left, f.bottom, f.right].map(Number);
    if (vals.some((v) => !Number.isFinite(v))) continue;
    let [top, left, bottom, right] = vals.map(clamp);
    if (top > bottom) [top, bottom] = [bottom, top];
    if (left > right) [left, right] = [right, left];
    const w = right - left, h = bottom - top;
    if (w < 40 || h < 40) continue;                 // a sliver: a rule line or a mis-read
    if (w * h > 950 * 950) continue;                 // the whole page
    const box = { page, top, left, bottom, right, label: String(f.label ?? "").trim().slice(0, 120) };
    if (kept.some((k) => k.page === page && overlap(k, box) > 0.8)) continue;
    kept.push(box);
  }
  return kept.sort((a, b) => a.page - b.page || a.top - b.top || a.left - b.left).slice(0, MAX_FIGURES_PER_CALL);
}

/** Intersection over the smaller box: 1 when one box sits inside the other. */
function overlap(a: FoundFigure, b: FoundFigure): number {
  const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  if (w <= 0 || h <= 0) return 0;
  const small = Math.min((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top));
  return (w * h) / small;
}

// ───────────────────────── placing reviewed figures into notes already made ─────────────────────────


const PLACE_SYSTEM = `
# YOUR JOB
- The user's notes for a study session are below (PREVIOUS NOTES). They were made from a document.
- FIGURES are pictures cut out of that same document: F1, F2, … (each with the page it came from).
- For each figure, choose where it belongs in the notes, and write its caption.

# WHERE
- "target": the section ref (e.g. "S2") whose points the figure illustrates. "NEW" only if no
  section fits at all.
- "insert_after": the ref of the point it illustrates (e.g. "S2.3") so the figure goes right after it,
  or "END" for the end of the section. Use "END" when the target is "NEW".
- Use each figure exactly once.

# CAPTION
- One short line saying what the figure shows (e.g. "Request path: user → edge → origin").
`.trim();

const MAX_ENUM = 500;

export function buildPlaceRequest(o: {
  sessionTitle: string; previousNotes: string; refs: RefMaps;
  figures: { key: string; page?: number; label?: string; image: ImageInput }[];
}): JsonRequest {
  const sRefs = [...o.refs.sectionByRef.keys()], bRefs = [...o.refs.bulletByRef.keys()];
  const keys = o.figures.map((f) => f.key);
  return {
    system: PLACE_SYSTEM,
    parts: [
      { text: `SESSION: ${o.sessionTitle}\n\n=== PREVIOUS NOTES ===\n${o.previousNotes}\n=== END OF PREVIOUS NOTES ===\n\n=== FIGURES ===` },
      ...o.figures.flatMap((f) => [
        { text: `[${f.key}]${f.page ? ` from page ${f.page}` : ""}${f.label ? ` — looks like: ${f.label}` : ""}` },
        { image: f.image },
      ]),
      { text: "=== END OF FIGURES ===" },
    ],
    schemaName: "figure_places",
    schema: {
      type: "object", additionalProperties: false,
      properties: {
        places: {
          type: "array",
          items: {
            type: "object", additionalProperties: false,
            properties: {
              figure: { type: "string", enum: keys },
              target: sRefs.length <= MAX_ENUM ? { type: "string", enum: ["NEW", ...sRefs] } : { type: "string" },
              insert_after: bRefs.length <= MAX_ENUM ? { type: "string", enum: ["END", ...bRefs] } : { type: "string" },
              caption: { type: "string" },
            },
            required: ["figure", "target", "insert_after", "caption"],
          },
        },
      },
      required: ["places"],
    },
  };
}

/**
 * apply_notes payload that adds each figure where the model said, into the existing sections. A figure
 * the model skipped, repeated or sent to an unknown place still goes in, under a "Figures" section.
 */
export function placePayload(output: unknown, refs: RefMaps, figures: { key: string; path: string; label?: string }[]) {
  const places = ((output as { places?: unknown[] })?.places ?? []) as Record<string, unknown>[];
  const byKey = new Map(figures.map((f) => [f.key.toUpperCase(), f]));
  const used = new Set<string>();
  const groups = new Map<string, { section_id: string | null; title: string; important: boolean; bullets: unknown[] }>();
  const add = (sectionId: string | null, bullet: unknown) => {
    const k = sectionId ?? "NEW";
    if (!groups.has(k)) groups.set(k, { section_id: sectionId, title: sectionId ? "" : "Figures", important: false, bullets: [] });
    groups.get(k)!.bullets.push(bullet);
  };
  for (const p of Array.isArray(places) ? places : []) {
    const key = String(p?.figure ?? "").replace(/[\[\]\s]/g, "").toUpperCase();
    const fig = byKey.get(key);
    if (!fig || used.has(key)) continue;
    used.add(key);
    const target = String(p?.target ?? "");
    const sectionId = refs.sectionByRef.get(target) ?? null;
    const after = sectionId && String(p?.insert_after ?? "").startsWith(`${target}.`) ? refs.bulletByRef.get(String(p.insert_after)) ?? null : null;
    const caption = String(p?.caption ?? "").trim().slice(0, 200) || (fig.label ?? "").slice(0, 200);
    add(sectionId, { kind: "figure", text: fig.path, after_bullet_id: after, important: false, caption, level: 0 });
  }
  for (const f of figures) {
    if (used.has(f.key.toUpperCase())) continue;
    add(null, { kind: "figure", text: f.path, after_bullet_id: null, important: false, caption: (f.label ?? "").slice(0, 200), level: 0 });
  }
  return { into_existing: true, sections: [...groups.values()] };
}

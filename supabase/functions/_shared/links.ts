// Links and references. Links typed or pasted as text are found here, exactly as written, so the
// AI never retypes them. The AI only gives titles and descriptions, and reads links printed in photos.

export interface KnownRef { id: string; number: number; url: string; title: string }
export interface ModelLink { key?: string; url?: string; title?: string; description?: string }
export interface RefToSave { key: string; url: string; title: string; description: string }
export interface SavedRef { key: string; id: string; number: number; title: string; url: string; is_new: boolean }

const URL_RE = /\bhttps?:\/\/[^\s<>"'`]+|\bwww\.[a-z0-9-]+\.[^\s<>"'`]+/gi;

/** Every web link in the text, in order, once each, without trailing punctuation. */
export function extractUrls(text: string): string[] {
  const out: string[] = [];
  for (const m of String(text).matchAll(URL_RE)) {
    let u = m[0];
    // trailing punctuation belongs to the sentence, not the link (a ")" only if it is unmatched)
    for (;;) {
      const last = u.slice(-1);
      if (/[.,;:!?'"\]}>]/.test(last)) { u = u.slice(0, -1); continue; }
      if (last === ")" && (u.match(/\(/g)?.length ?? 0) < (u.match(/\)/g)?.length ?? 0)) { u = u.slice(0, -1); continue; }
      break;
    }
    if (/^www\./i.test(u)) u = "https://" + u;
    if (!out.includes(u)) out.push(u);
  }
  return out;
}

/** A link the AI read from a photo, if it looks like a real web link. */
export function cleanModelUrl(url: string): string | null {
  let u = String(url ?? "").trim().replace(/^<|>$/g, "");
  if (/^www\./i.test(u)) u = "https://" + u;
  if (!/^https?:\/\/[^\s/$.?#][^\s]*$/i.test(u)) return null;
  return u;
}

export function linksBlock(known: KnownRef[], textUrls: string[]): string {
  const lines = [
    ...known.map((r) => `[R${r.number}] ${r.title} — ${r.url}`),
    ...textUrls.map((u, i) => `[L${i + 1}] ${u}`),
  ];
  return `=== KNOWN LINKS ===\n${lines.join("\n") || "(none)"}\n=== END OF KNOWN LINKS ===`;
}

export const LINK_ITEM_SCHEMA = {
  type: "object", additionalProperties: false,
  properties: { key: { type: "string" }, url: { type: "string" }, title: { type: "string" }, description: { type: "string" } },
  required: ["key", "url", "title", "description"],
};

/** What to save: every text link (L1…), plus links the AI read from photos (P1…). */
export function refsToSave(textUrls: string[], modelLinks: ModelLink[]): RefToSave[] {
  const byKey = new Map<string, ModelLink>();
  for (const l of modelLinks ?? []) if (l?.key) byKey.set(String(l.key).replace(/[\[\]\s]/g, "").toUpperCase(), l);
  const out: RefToSave[] = textUrls.map((url, i) => {
    const l = byKey.get(`L${i + 1}`);
    return { key: `L${i + 1}`, url, title: clip(l?.title, 120), description: clip(l?.description, 300) };
  });
  for (const [key, l] of byKey) {
    if (!/^P\d+$/.test(key)) continue;
    const url = cleanModelUrl(l.url ?? "");
    if (url && !out.some((o) => o.url === url)) out.push({ key, url, title: clip(l.title, 120), description: clip(l.description, 300) });
  }
  return out;
}

const clip = (s: unknown, n: number) => String(s ?? "").trim().slice(0, n);

/** Marker → [[ref:id]] in a note. Unknown markers are dropped; a pasted link becomes its marker. */
export function citeRefs(text: string, ids: Map<string, string>, textUrls: string[]): string {
  let t = text;
  textUrls.forEach((u, i) => { if (t.includes(u)) t = t.split(u).join(`[L${i + 1}]`); });
  t = t.replace(/\[\s*([RLP])\s*(\d+)\s*\]/gi, (_, k: string, n: string) => {
    const id = ids.get(`${k.toUpperCase()}${n}`);
    return id ? `[[ref:${id}]]` : "";
  });
  return t.replace(/[ \t]{2,}/g, " ").replace(/\s+(\[\[ref:)/g, " $1").trim();
}

/** [[ref:id]] → [R#] when notes are shown to the AI again. */
export function refsForModel(text: string, numbers: Map<string, number>): string {
  return text.replace(/\[\[ref:([0-9a-fA-F-]+)\]\]/g, (_, id: string) => numbers.has(id) ? `[R${numbers.get(id)}]` : "");
}

export function stripRefs(text: string): string {
  return text.replace(/\s*\[\[ref:[0-9a-fA-F-]+\]\]/g, "");
}

export function refList(saved: SavedRef[]): string {
  return saved.map((r) => `[${r.number}] ${r.title}`).join(", ");
}

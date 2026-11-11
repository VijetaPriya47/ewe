// Provider adapters: OpenAI-style (OpenAI + any OpenAI-compatible URL), Claude, Gemini.
// The key arrives with each request from the phone and is never stored.

import Anthropic from "npm:@anthropic-ai/sdk@1.6.0";

export type ProviderKind = "openai" | "anthropic" | "gemini" | "compatible";

export interface ProviderCfg {
  kind: ProviderKind;
  api_key: string;
  model: string;
  base_url?: string; // required for "compatible"; optional override otherwise
}

export interface ImageInput { mime: string; data: string } // base64, no data: prefix
export type Part = { text: string } | { image: ImageInput };

export interface JsonRequest {
  system: string;
  parts: Part[];
  schema: Record<string, unknown>;
  schemaName: string;
}

export class ProviderError extends Error {
  constructor(message: string, public status = 502, public detail?: string) { super(message); }
}

export function modelKey(cfg: ProviderCfg): string { return `${cfg.kind}:${cfg.model}`; }

export function checkCfg(cfg: unknown, what: string): ProviderCfg {
  const c = cfg as ProviderCfg | undefined;
  if (!c || !c.kind || !c.model) throw new ProviderError(`Pick a provider and model for ${what} in Settings.`, 400);
  if (!["openai", "anthropic", "gemini", "compatible"].includes(c.kind)) {
    throw new ProviderError(`Unknown provider "${c.kind}".`, 400);
  }
  if (c.kind === "compatible" && !c.base_url) throw new ProviderError("The OpenAI-compatible provider needs a URL.", 400);
  if (!c.api_key && c.kind !== "compatible") throw new ProviderError(`Add your ${c.kind} key in Settings.`, 400);
  return c;
}

function baseUrl(cfg: ProviderCfg): string {
  const fallback = cfg.kind === "gemini"
    ? "https://generativelanguage.googleapis.com/v1beta"
    : "https://api.openai.com/v1";
  return (cfg.base_url?.trim() || fallback).replace(/\/+$/, "");
}

function parseJson(text: string): unknown {
  const t = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  try { return JSON.parse(t); } catch { /* fall through */ }
  const a = t.indexOf("{"), b = t.lastIndexOf("}");
  if (a >= 0 && b > a) { try { return JSON.parse(t.slice(a, b + 1)); } catch { /* fall through */ } }
  throw new ProviderError("The model did not return valid JSON. Try again or pick another model.");
}

const schemaHint = (schema: unknown) =>
  `\n\n# OUTPUT FORMAT\nReturn ONLY one JSON object matching this JSON Schema. No prose, no code fences.\n${JSON.stringify(schema)}`;

/** One model call that returns JSON matching `schema`. */
export function generateJson(cfg: ProviderCfg, req: JsonRequest): Promise<unknown> {
  switch (cfg.kind) {
    case "anthropic": return claudeJson(cfg, req);
    case "gemini": return geminiJson(cfg, req);
    default: return openaiJson(cfg, req);
  }
}

// ───────────────────────── OpenAI-style ─────────────────────────
async function openaiJson(cfg: ProviderCfg, req: JsonRequest): Promise<unknown> {
  const content = req.parts.map((p) =>
    "text" in p
      ? { type: "text", text: p.text }
      : { type: "image_url", image_url: { url: `data:${p.image.mime};base64,${p.image.data}`, detail: "high" } });

  const call = (strict: boolean) =>
    fetch(`${baseUrl(cfg)}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(cfg.api_key ? { Authorization: `Bearer ${cfg.api_key}` } : {}) },
      body: JSON.stringify({
        model: cfg.model,
        messages: [
          { role: "system", content: strict ? req.system : req.system + schemaHint(req.schema) },
          { role: "user", content },
        ],
        response_format: strict
          ? { type: "json_schema", json_schema: { name: req.schemaName, strict: true, schema: req.schema } }
          : { type: "json_object" },
      }),
    });

  let res = await call(true);
  if (res.status === 400 || res.status === 422) {
    // Some OpenAI-compatible servers only support plain JSON mode.
    const first = await res.text();
    res = await call(false);
    if (!res.ok) throw new ProviderError(`${cfg.kind} error ${res.status}`, 502, first.slice(0, 800));
  }
  if (!res.ok) throw new ProviderError(`${cfg.kind} error ${res.status}`, 502, (await res.text()).slice(0, 800));
  const body = await res.json();
  const msg = body.choices?.[0]?.message;
  if (msg?.refusal) throw new ProviderError("The model refused this content.", 502, msg.refusal);
  const text = typeof msg?.content === "string"
    ? msg.content
    : (msg?.content ?? []).map((c: { text?: string }) => c.text ?? "").join("");
  return parseJson(text);
}

// ───────────────────────── Claude ─────────────────────────
async function claudeJson(cfg: ProviderCfg, req: JsonRequest): Promise<unknown> {
  const client = new Anthropic({ apiKey: cfg.api_key, ...(cfg.base_url ? { baseURL: cfg.base_url } : {}) });
  const content: Anthropic.ContentBlockParam[] = req.parts.map((p) =>
    "text" in p
      ? { type: "text", text: p.text }
      : {
        type: "image",
        source: { type: "base64", media_type: p.image.mime as "image/jpeg" | "image/png" | "image/webp" | "image/gif", data: p.image.data },
      });

  const run = async (structured: boolean) => {
    const stream = client.messages.stream({
      model: cfg.model,
      max_tokens: 32000,
      system: structured ? req.system : req.system + schemaHint(req.schema),
      messages: [{ role: "user", content }],
      ...(structured ? { output_config: { format: { type: "json_schema", schema: req.schema } } } : {}),
    });
    return await stream.finalMessage();
  };

  let msg: Anthropic.Message;
  try {
    try {
      msg = await run(true);
    } catch (e) {
      // Older Claude models have no structured outputs or a smaller output cap:
      // fall back to a plain request with the schema in the prompt.
      if (!(e instanceof Anthropic.BadRequestError)) throw e;
      try {
        msg = await run(false);
      } catch (e2) {
        if (!(e2 instanceof Anthropic.BadRequestError) || !/max_tokens/.test(e2.message)) throw e2;
        const res = await client.messages.create({
          model: cfg.model, max_tokens: 4096,
          system: req.system + schemaHint(req.schema),
          messages: [{ role: "user", content }],
        });
        msg = res;
      }
    }
  } catch (e) {
    if (e instanceof Anthropic.AuthenticationError) throw new ProviderError("Claude rejected the API key.", 502, e.message);
    if (e instanceof Anthropic.RateLimitError) throw new ProviderError("Claude rate limit reached. Try again in a minute.", 502, e.message);
    if (e instanceof Anthropic.APIError) throw new ProviderError(`Claude error ${e.status ?? ""}`.trim(), 502, e.message.slice(0, 800));
    throw e;
  }
  if (msg.stop_reason === "refusal") throw new ProviderError("Claude declined this content.");
  if (msg.stop_reason === "max_tokens") throw new ProviderError("The answer was too long for this model. Send fewer pages at once.");
  const text = msg.content.map((b) => (b.type === "text" ? b.text : "")).join("");
  return parseJson(text);
}

// ───────────────────────── Gemini ─────────────────────────
/** Gemini's responseSchema is an OpenAPI subset: drop keys it rejects. */
export function toGeminiSchema(s: unknown): unknown {
  if (Array.isArray(s)) return s.map(toGeminiSchema);
  if (!s || typeof s !== "object") return s;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(s as Record<string, unknown>)) {
    if (k === "additionalProperties" || k === "$schema") continue;
    out[k] = k === "properties"
      ? Object.fromEntries(Object.entries(v as Record<string, unknown>).map(([pk, pv]) => [pk, toGeminiSchema(pv)]))
      : toGeminiSchema(v);
  }
  return out;
}

async function geminiJson(cfg: ProviderCfg, req: JsonRequest): Promise<unknown> {
  const model = cfg.model.replace(/^models\//, "");
  const res = await fetch(`${baseUrl(cfg)}/models/${encodeURIComponent(model)}:generateContent`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": cfg.api_key },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: req.system }] },
      contents: [{
        role: "user",
        parts: req.parts.map((p) =>
          "text" in p ? { text: p.text } : { inlineData: { mimeType: p.image.mime, data: p.image.data } }),
      }],
      generationConfig: { responseMimeType: "application/json", responseSchema: toGeminiSchema(req.schema) },
    }),
  });
  if (!res.ok) throw new ProviderError(`Gemini error ${res.status}`, 502, (await res.text()).slice(0, 800));
  const body = await res.json();
  if (body.promptFeedback?.blockReason) throw new ProviderError(`Gemini blocked this content (${body.promptFeedback.blockReason}).`);
  const text = (body.candidates?.[0]?.content?.parts ?? []).map((p: { text?: string }) => p.text ?? "").join("");
  if (!text) throw new ProviderError("Gemini returned no text.", 502, JSON.stringify(body).slice(0, 500));
  return parseJson(text);
}

// ───────────────────────── Embeddings ─────────────────────────
export async function embed(cfg: ProviderCfg, texts: string[]): Promise<number[][]> {
  if (texts.length === 0) return [];
  if (cfg.kind === "anthropic") throw new ProviderError("Claude has no embeddings API — pick OpenAI, Gemini or an OpenAI-compatible provider.", 400);
  const clipped = texts.map((t) => t.slice(0, 8000));

  if (cfg.kind === "gemini") {
    const model = `models/${cfg.model.replace(/^models\//, "")}`;
    const out: number[][] = [];
    for (let i = 0; i < clipped.length; i += 100) {
      const res = await fetch(`${baseUrl(cfg)}/${model}:batchEmbedContents`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": cfg.api_key },
        body: JSON.stringify({ requests: clipped.slice(i, i + 100).map((t) => ({ model, content: { parts: [{ text: t }] } })) }),
      });
      if (!res.ok) throw new ProviderError(`Gemini embeddings error ${res.status}`, 502, (await res.text()).slice(0, 800));
      const body = await res.json();
      for (const e of body.embeddings ?? []) out.push(e.values);
    }
    if (out.length !== clipped.length) throw new ProviderError("Gemini returned the wrong number of embeddings.");
    return out;
  }

  const res = await fetch(`${baseUrl(cfg)}/embeddings`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(cfg.api_key ? { Authorization: `Bearer ${cfg.api_key}` } : {}) },
    body: JSON.stringify({ model: cfg.model, input: clipped }),
  });
  if (!res.ok) throw new ProviderError(`${cfg.kind} embeddings error ${res.status}`, 502, (await res.text()).slice(0, 800));
  const body = await res.json();
  const data = [...(body.data ?? [])].sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
  if (data.length !== clipped.length) throw new ProviderError("The provider returned the wrong number of embeddings.");
  return data.map((d: { embedding: number[] }) => d.embedding);
}

// deno test --allow-net supabase/functions/_shared/shared_test.ts
import { assert, assertEquals, assertRejects } from "jsr:@std/assert@1";
import { buildNotesRequest, buildSchema, renderPreviousNotes, summarize, toApplyPayload } from "./notes.ts";
import { buildPlacementRequest, resolvePlacement } from "./placement.ts";
import { embed, generateJson, type ProviderCfg, ProviderError, toGeminiSchema } from "./providers.ts";
import { candidates, repairMermaid } from "./mermaid_repair.ts";

const sections = [
  { id: "sec-1", title: "Basics", bullets: [{ id: "b-1", content: "CDN = servers near users" }, { id: "b-2", content: "Lower latency" }] },
  { id: "sec-2", title: "Edge", bullets: [{ id: "b-3", content: "PoP = CDN location" }] },
];

Deno.test("previous notes get refs for topics only", () => {
  const t = renderPreviousNotes(sections, true);
  assert(t.text.includes("## [S1] Basics") && t.text.includes("- [S2.1] PoP"));
  assertEquals(t.refs.bulletByRef.get("S1.2"), "b-2");
  assert(!renderPreviousNotes(sections, false).text.includes("["));
  assert(renderPreviousNotes([], true).text.includes("none yet"));
});

Deno.test("prompt has the three labelled parts and images in between", () => {
  const { refs, text } = renderPreviousNotes(sections, true);
  const r = buildNotesRequest({ sessionType: "topic", sessionTitle: "CDN", previousNotes: text, refs, newText: "ttl", images: [{ mime: "image/jpeg", data: "AAAA" }] });
  assert(r.system.includes("Make notes ONLY for the NEW CONTENT") && r.system.includes("this session is a TOPIC"));
  assertEquals(r.parts.length, 3);
  const head = (r.parts[0] as { text: string }).text;
  assert(head.includes("=== PREVIOUS NOTES (context only") && head.includes("=== NEW CONTENT (make notes ONLY from this) ==="));
  assert("image" in r.parts[1]);
  const p = buildNotesRequest({ sessionType: "project", sessionTitle: "Sudoku", previousNotes: "x", refs, newText: "y", images: [] });
  assert(p.system.includes("PROJECT") && !JSON.stringify(p.schema).includes("insert_after"));
});

Deno.test("schema only allows real refs", () => {
  const { refs } = renderPreviousNotes(sections, true);
  const s = JSON.stringify(buildSchema("topic", refs));
  assert(s.includes('"enum":["NEW","S1","S2"]') && s.includes('"enum":["END","S1.1","S1.2","S2.1"]'));
  assert(s.includes('"kind":{"type":"string","enum":["text","mermaid","code","table"]}') && s.includes('"required":["kind","text","lang","important","insert_after"]'));
  assert(s.includes('"required":["target","title","important","bullets"]'));
});

Deno.test("model answer → apply payload, bad refs ignored", () => {
  const { refs } = renderPreviousNotes(sections, true);
  const p = toApplyPayload("topic", { sections: [
    { target: "S1", title: "Basics", bullets: [{ text: " a ", insert_after: "S1.1" }, { text: "b", insert_after: "S2.1" }, { text: "", insert_after: "END" }] },
    { target: "S9", title: "New one", bullets: [{ text: "c", insert_after: "END" }] },
    { target: "NEW", title: "Empty", bullets: [] },
  ] }, refs);
  assertEquals(p.sections.length, 2);
  assertEquals(p.sections[0], { section_id: "sec-1", title: "Basics", important: false, bullets: [
    { kind: "text", text: "a", after_bullet_id: "b-1", important: false }, { kind: "text", text: "b", after_bullet_id: null, important: false }] });
  assertEquals(p.sections[1].section_id, null);
  const proj = toApplyPayload("project", { sections: [{ target: "S1", title: "T", bullets: [{ text: "x", insert_after: "S1.1" }] }] }, refs);
  assertEquals(proj.sections[0], { section_id: null, title: "T", important: false, bullets: [{ kind: "text", text: "x", after_bullet_id: null, important: false }] });
});

Deno.test("diagrams: fences stripped, empty ones dropped, existing ones summarised", () => {
  const { refs } = renderPreviousNotes(sections, true);
  const p = toApplyPayload("project", { sections: [{ title: "Flow", bullets: [
    { kind: "text", text: "Generator fills grid" },
    { kind: "mermaid", text: "```mermaid\nflowchart TD\n  A --> B\n```" },
    { kind: "mermaid", text: "```mermaid\n```" },
    { kind: "weird", text: "falls back to text" },
  ] }] }, refs);
  assertEquals(p.sections[0].bullets.map((b) => [b.kind, b.text]), [
    ["text", "Generator fills grid"], ["mermaid", "flowchart TD\n  A --> B"], ["text", "falls back to text"]]);
  const prev = renderPreviousNotes([{ id: "s", title: "Flow", bullets: [{ id: "d", content: "flowchart TD\n  A --> B", kind: "mermaid" }] }], true);
  assert(prev.text.includes("- [S1.1] (diagram already drawn: flowchart TD A --> B)"));
  const r = buildNotesRequest({ sessionType: "topic", sessionTitle: "x", previousNotes: "", refs, newText: "y", images: [] });
  assert(r.system.includes("# DIAGRAMS (optional") && r.system.includes("never replaces text"));
});

Deno.test("code, tables and important marks", () => {
  const { refs } = renderPreviousNotes(sections, true);
  const p = toApplyPayload("project", { sections: [{ title: "Code", important: true, bullets: [
    { kind: "text", text: "Key rule", important: true },
    { kind: "code", text: "```Python\n\ndef f(x):\n    return x\n```", lang: " Python " },
    { kind: "table", text: "| a | b |\n| --- | --- |\n| 1 | 2 |" },
    { kind: "table", text: "not really a table" },
    { kind: "code", text: "```\n```" },
  ] }] }, refs);
  const g = p.sections[0];
  assertEquals(g.important, true);
  assertEquals(g.bullets.map((b) => [b.kind, b.important]), [["text", true], ["code", false], ["table", false], ["text", false]]);
  assertEquals(g.bullets[1].text, "def f(x):\n    return x");
  assertEquals((g.bullets[1] as { lang?: string }).lang, "python");
  assert(!("lang" in g.bullets[0]));
  const prev = renderPreviousNotes([{ id: "s", title: "T", bullets: [
    { id: "c", content: "def f():\n  pass", kind: "code" }, { id: "t", content: "| a |\n| --- |\n| 1 |", kind: "table" }] }], false);
  assert(prev.text.includes("(code block: def f(): pass)") && prev.text.includes("(table: | a | ; | --- | ; | 1 |)"));
  assertEquals(summarize({ new_bullets: 4, new_diagrams: 0, new_tables: 1, new_code: 1,
    sections: [{ title: "Code", is_new: true, added: 4, inline: 0, tables: 1, code: 1 }] }, 0),
    "Added **2 points**, **1 table** and **1 code block** in a new section: **Code**.");
});

Deno.test("mermaid repair: one-line flowcharts split, labels quoted, good code untouched", () => {
  const oneLine = 'flowchart TD I["Index"] --> H["Hash"] I --> T["Tree"] H --> EG["Even grid"] T --> QT["Quadtree"]';
  assertEquals(repairMermaid(oneLine),
    'flowchart TD\n  I["Index"] --> H["Hash"]\n  I --> T["Tree"]\n  H --> EG["Even grid"]\n  T --> QT["Quadtree"]');
  assertEquals(repairMermaid("flowchart TD\n  A[Fetch IDs (parallel)] --> B"), 'flowchart TD\n  A["Fetch IDs (parallel)"] --> B');
  const good = 'flowchart TD\nA["x"] -->|"y"| B["z"]';
  assertEquals(repairMermaid(good), good);
  assertEquals(repairMermaid("sequenceDiagram\n  A->>B: hi"), "sequenceDiagram\n  A->>B: hi");
  assertEquals(repairMermaid("flowchart TD subgraph S A --> B end"), "flowchart TD subgraph S A --> B end"); // too risky: left alone
  // a model answer gets repaired before it is saved
  const { refs } = renderPreviousNotes([], true);
  const p = toApplyPayload("project", { sections: [{ title: "Index", bullets: [{ kind: "mermaid", text: oneLine }] }] }, refs);
  assert(p.sections[0].bullets[0].text.startsWith('flowchart TD\n  I["Index"]'));
});

Deno.test("the app's repair.js and this TypeScript copy agree", async () => {
  const js = await Deno.readTextFile(new URL("../../../app/src/main/assets/mermaid/repair.js", import.meta.url));
  const App = new Function(js + "\nreturn MermaidRepair;")() as { candidates: (c: string) => string[] };
  const samples = [
    'flowchart TD I["Index"] --> H["Hash"] I --> T["Tree"]',
    "graph LR A[Client] -->|GET /a.js| B{Cached?} B -->|yes| C[Edge] B -->|no| D[Origin] D --> C",
    "flowchart TD A & B --> C C -- retries --> D D -.-> E((Done))",
    "flowchart TD\n  A[Fetch IDs (parallel)] --> B[Rank: by distance]",
    "```mermaid\nflowchart LR\n  A --> B\n```",
    "flowchart TD; A-->B; B-->C",
    "mindmap\n  root((CDN))\n    Basics",
    "flowchart TD subgraph S A --> B end",
  ];
  for (const c of samples) assertEquals(candidates(c), App.candidates(c), c);
});

Deno.test("chat summary wording", () => {
  assertEquals(summarize({ new_bullets: 4, new_diagrams: 1, sections: [{ title: "Flow", is_new: true, added: 4, inline: 0, diagrams: 1 }] }, 0),
    "Added **3 points** and **1 diagram** in a new section: **Flow**.");
  assertEquals(summarize({ new_bullets: 3, new_diagrams: 1, sections: [{ title: "Edge", is_new: false, added: 3, inline: 2, diagrams: 1 }] }, 0),
    "Added **2 points** and **1 diagram**: 2 inline in **Edge** + 1 diagram.");
  assertEquals(summarize({ new_bullets: 12, sections: [{ title: "Basics", is_new: true, added: 7, inline: 0 }, { title: "Edge servers", is_new: true, added: 5, inline: 0 }] }, 0),
    "Added **12 points** in 2 new sections: **Basics**, **Edge servers**.");
  assertEquals(summarize({ new_bullets: 5, sections: [{ title: "Edge servers", is_new: false, added: 3, inline: 3 }, { title: "Caching", is_new: true, added: 2, inline: 0 }] }, 1),
    "Added **5 points**: 3 inline in **Edge servers**, 2 in a new section **Caching**.\n1 point skipped: already in your notes.");
  assert(summarize({ new_bullets: 0, sections: [] }, 3).startsWith("Nothing new"));
});

Deno.test("placement: only valid (action, target) pairs do anything", () => {
  const secs = [{ ref: "C1", section_id: "s", session_id: "t", topic: "CDN", section: "Caching", preview: "p" }];
  const tops = [{ ref: "T1", session_id: "t", topic: "CDN", preview: "p" }];
  const r = buildPlacementRequest({ fromSession: "Sudoku", groupTitle: "Assets", bullets: ["cache static assets"], sections: secs, topics: tops });
  assert(JSON.stringify(r.schema).includes('"enum":["NONE","C1","T1"]'));
  assertEquals(resolvePlacement({ action: "ADD_TO_SECTION", target: "C1" }, secs, tops).action, "ADD_TO_SECTION");
  assertEquals(resolvePlacement({ action: "NEW_SECTION_IN_TOPIC", target: "T1" }, secs, tops).action, "NEW_SECTION_IN_TOPIC");
  assertEquals(resolvePlacement({ action: "ADD_TO_SECTION", target: "T1" }, secs, tops).action, "KEEP_IN_CURRENT");
  assertEquals(resolvePlacement({ action: "ADD_TO_SECTION", target: "C7" }, secs, tops).action, "KEEP_IN_CURRENT");
  assertEquals(resolvePlacement("garbage", secs, tops).action, "KEEP_IN_CURRENT");
});

Deno.test("gemini schema drops additionalProperties but keeps a property of that name", () => {
  const g = toGeminiSchema({ type: "object", additionalProperties: false, properties: { a: { type: "string", enum: ["x"] } }, required: ["a"] }) as Record<string, unknown>;
  assertEquals(g, { type: "object", properties: { a: { type: "string", enum: ["x"] } }, required: ["a"] });
});

// ── adapters against a local mock of each provider's wire format ──
const seen: { path: string; headers: Headers; body: Record<string, unknown> }[] = [];
const answer = { sections: [], skipped_duplicates: 0 };
const server = Deno.serve({ port: 0, onListen: () => {} }, async (req) => {
  const url = new URL(req.url);
  const body = await req.json().catch(() => ({}));
  seen.push({ path: url.pathname, headers: req.headers, body });
  const j = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });
  if (url.pathname === "/oai/chat/completions") return j({ choices: [{ message: { content: JSON.stringify(answer) } }] });
  if (url.pathname === "/weak/chat/completions") {
    return body.response_format?.type === "json_schema" ? j({ error: "json_schema unsupported" }, 400)
      : j({ choices: [{ message: { content: "```json\n" + JSON.stringify(answer) + "\n```" } }] });
  }
  if (url.pathname === "/oai/embeddings") return j({ data: (body.input as string[]).map((_, i) => ({ index: i, embedding: [i, 1] })).reverse() });
  if (url.pathname.endsWith(":generateContent")) return j({ candidates: [{ content: { parts: [{ text: JSON.stringify(answer) }] } }] });
  if (url.pathname.endsWith(":batchEmbedContents")) return j({ embeddings: (body.requests as unknown[]).map((_, i) => ({ values: [i, 2] })) });
  if (url.pathname === "/claude/v1/messages") {
    const text = JSON.stringify(answer);
    const ev = (type: string, data: unknown) => `event: ${type}\ndata: ${JSON.stringify({ type, ...(data as object) })}\n\n`;
    const sse = ev("message_start", { message: { id: "msg_1", type: "message", role: "assistant", model: body.model, content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 1, output_tokens: 1 } } }) +
      ev("content_block_start", { index: 0, content_block: { type: "text", text: "" } }) +
      ev("content_block_delta", { index: 0, delta: { type: "text_delta", text } }) +
      ev("content_block_stop", { index: 0 }) +
      ev("message_delta", { delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 5 } }) +
      ev("message_stop", {});
    return new Response(sse, { headers: { "content-type": "text/event-stream" } });
  }
  return j({ error: "not found" }, 404);
});
const base = `http://127.0.0.1:${(server.addr as Deno.NetAddr).port}`;
const req = { system: "sys", parts: [{ text: "hello" }, { image: { mime: "image/jpeg", data: "QUJD" } }], schema: { type: "object", additionalProperties: false, properties: {}, required: [] }, schemaName: "notes" };
const last = () => seen[seen.length - 1];

Deno.test({ name: "provider adapters speak each wire format", sanitizeResources: false, sanitizeOps: false, fn: async () => {
  const oai: ProviderCfg = { kind: "openai", api_key: "sk-test", model: "gpt-x", base_url: `${base}/oai` };
  assertEquals(await generateJson(oai, req), answer);
  assertEquals(last().headers.get("authorization"), "Bearer sk-test");
  const rf = last().body.response_format as { type: string; json_schema: { strict: boolean } };
  assert(rf.type === "json_schema" && rf.json_schema.strict);
  const userParts = (last().body.messages as { content: { type: string; image_url?: { url: string } }[] }[])[1].content;
  assertEquals(userParts[1].image_url?.url, "data:image/jpeg;base64,QUJD");

  // server without json_schema support → falls back to JSON mode with the schema in the prompt
  assertEquals(await generateJson({ kind: "compatible", api_key: "", model: "llama", base_url: `${base}/weak` }, req), answer);
  assert(((last().body.messages as { content: string }[])[0].content).includes("OUTPUT FORMAT"));

  assertEquals(await embed(oai, ["a", "b"]), [[0, 1], [1, 1]]);

  const gem: ProviderCfg = { kind: "gemini", api_key: "AIza", model: "gemini-flash", base_url: `${base}/v1beta` };
  assertEquals(await generateJson(gem, req), answer);
  assertEquals(last().path, "/v1beta/models/gemini-flash:generateContent");
  assertEquals(last().headers.get("x-goog-api-key"), "AIza");
  const gc = last().body.generationConfig as { responseMimeType: string; responseSchema: Record<string, unknown> };
  assert(gc.responseMimeType === "application/json" && !("additionalProperties" in gc.responseSchema));
  assertEquals(await embed({ ...gem, model: "gemini-embedding-001" }, ["a", "b"]), [[0, 2], [1, 2]]);

  const claude: ProviderCfg = { kind: "anthropic", api_key: "sk-ant-test", model: "claude-opus-5-5", base_url: `${base}/claude` };
  assertEquals(await generateJson(claude, req), answer);
  assert((last().headers.get("authorization") ?? last().headers.get("x-api-key") ?? "").includes("sk-ant-test"));
  const cb = last().body as { system: string; output_config: { format: { type: string } }; messages: { content: { type: string; source?: { type: string; data: string } }[] }[] };
  assertEquals(cb.output_config.format.type, "json_schema");
  assertEquals(cb.messages[0].content[1].source, { type: "base64", media_type: "image/jpeg", data: "QUJD" } as never);
  await assertRejects(() => embed(claude, ["a"]), ProviderError, "no embeddings API");
  await server.shutdown();
} });

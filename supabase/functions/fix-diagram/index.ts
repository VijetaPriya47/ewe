// POST /functions/v1/fix-diagram — last resort when a saved Mermaid diagram cannot be drawn and the
// phone's own repairs did not help. Asks the notes model once to correct the code.
//
// Body: { "code": string, "error": string, "provider": ProviderCfg }  →  { "code": string }
// The app checks the result draws before saving it. Keys are used for this call only.

import { HttpError, requireUser, serve, userClient } from "../_shared/http.ts";
import { checkCfg, generateJson } from "../_shared/providers.ts";
import { cleanMermaid } from "../_shared/notes.ts";
import { repairMermaid } from "../_shared/mermaid_repair.ts";

const SYSTEM = `
# YOUR JOB
- You get Mermaid diagram code that fails to parse, and the parser's error message.
- Return the corrected Mermaid code. Nothing else.

# RULES
- Keep the same diagram type, nodes, labels and connections. Fix only what stops it parsing.
- One statement per line (real line breaks), first line is the diagram type, e.g. "flowchart TD".
- Put node labels and edge labels in double quotes: A["Fetch IDs (parallel)"], A -->|"miss"| B.
- No \`\`\` fences, no comments, no explanation.
`.trim();

serve(async (req, body) => {
  const code = String(body.code ?? "");
  const error = String(body.error ?? "").slice(0, 500);
  if (!code.trim()) throw new HttpError("code is required");
  if (code.length > 8000) throw new HttpError("That diagram is too long to fix.");
  const provider = checkCfg(body.provider, "notes");
  await requireUser(req, userClient(req));

  const out = await generateJson(provider, {
    system: SYSTEM,
    parts: [{ text: `=== ERROR ===\n${error || "(none given)"}\n\n=== MERMAID CODE ===\n${code}\n=== END ===` }],
    schemaName: "mermaid_fix",
    schema: { type: "object", additionalProperties: false, properties: { code: { type: "string" } }, required: ["code"] },
  }) as { code?: string };
  const fixed = repairMermaid(cleanMermaid(String(out?.code ?? "")));
  if (!fixed) throw new HttpError("The model returned no diagram.", 502);
  return { code: fixed };
});

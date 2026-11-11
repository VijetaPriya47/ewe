// Placement: one tiny call with exactly three possible answers.

import type { JsonRequest } from "./providers.ts";

export interface SectionCandidate { ref: string; section_id: string; session_id: string; topic: string; section: string; preview: string }
export interface TopicCandidate { ref: string; session_id: string; topic: string; preview: string }
export type Action = "ADD_TO_SECTION" | "NEW_SECTION_IN_TOPIC" | "KEEP_IN_CURRENT";

const SYSTEM = `
# YOUR JOB
- A user took NEW NOTES in one study session. Decide whether these notes ALSO belong inside one of the user's existing Topics.
- Answer with exactly one action and one target. Nothing else.

# THE THREE ACTIONS
- ADD_TO_SECTION: the new notes are about the same subject as one CANDIDATE SECTION and would clearly help someone revising it. target = that section's ref (C1, C2, …).
- NEW_SECTION_IN_TOPIC: the new notes clearly belong to one CANDIDATE TOPIC but fit none of its listed sections. target = that topic's ref (T1, T2, …).
- KEEP_IN_CURRENT: the notes are not really about any candidate, or only loosely related. target = "NONE".

# RULES
- Be strict. A shared word is not enough; the subject must match. When unsure, answer KEEP_IN_CURRENT.
- Never invent a ref.
`.trim();

export function buildPlacementRequest(opts: {
  fromSession: string; groupTitle: string; bullets: string[];
  sections: SectionCandidate[]; topics: TopicCandidate[];
}): JsonRequest {
  const text =
`=== NEW NOTES (from session "${opts.fromSession}", group "${opts.groupTitle}") ===
${opts.bullets.map((b) => `- ${b}`).join("\n")}

=== CANDIDATE SECTIONS ===
${opts.sections.map((c) => `[${c.ref}] ${c.preview}`).join("\n\n") || "(none)"}

=== CANDIDATE TOPICS ===
${opts.topics.map((t) => `[${t.ref}] ${t.preview}`).join("\n\n") || "(none)"}
=== END ===`;

  return {
    system: SYSTEM,
    parts: [{ text }],
    schemaName: "placement",
    schema: {
      type: "object", additionalProperties: false,
      properties: {
        action: { type: "string", enum: ["ADD_TO_SECTION", "NEW_SECTION_IN_TOPIC", "KEEP_IN_CURRENT"] },
        target: { type: "string", enum: ["NONE", ...opts.sections.map((c) => c.ref), ...opts.topics.map((t) => t.ref)] },
      },
      required: ["action", "target"],
    },
  };
}

/** Anything that is not a valid (action, target) pair means "keep in current". */
export function resolvePlacement(out: unknown, sections: SectionCandidate[], topics: TopicCandidate[]):
  { action: "ADD_TO_SECTION"; section: SectionCandidate } | { action: "NEW_SECTION_IN_TOPIC"; topic: TopicCandidate } | { action: "KEEP_IN_CURRENT" } {
  const o = (out ?? {}) as { action?: string; target?: string };
  if (o.action === "ADD_TO_SECTION") {
    const section = sections.find((c) => c.ref === o.target);
    if (section) return { action: "ADD_TO_SECTION", section };
  }
  if (o.action === "NEW_SECTION_IN_TOPIC") {
    const topic = topics.find((t) => t.ref === o.target);
    if (topic) return { action: "NEW_SECTION_IN_TOPIC", topic };
  }
  return { action: "KEEP_IN_CURRENT" };
}

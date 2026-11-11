# Changelog

Every release of ewe is listed here, newest first. Install a release from the repository's
**Releases** page; each build installs over the previous one.

## 0.2.0 — 2026-11-11

### Added
- **Listen to notes**: a "Listen to notes" button in every session's Notes tab reads the notes
  aloud with the phone's own voice (no AI, works offline). The line being read is highlighted and
  the list follows along. Controls: pause/resume, previous/next section, speed (0.8×–1.5×), stop.
  Reading continues with the screen locked and stops when you leave the session.
- **Mermaid diagrams in notes**: when the material shows a figure or describes a process, flow,
  sequence or structure, the notes AI may add a small diagram (at most 2 per upload). Diagrams are
  drawn in the Notes tab with the bundled Mermaid library, so they show offline too.
  A subtle bar under each diagram has **Copy** (copies the Mermaid code) and **Code** (shows the
  code instead of the drawing). Tap "Diagram · Mermaid" for its date, edit and delete.
  If the code can't be drawn, the code is shown with the reason.
- Chat replies count diagrams separately, e.g. "Added 3 points and 1 diagram".
- Search shows matching diagrams as code.

### Changed
- Diagrams are extra: every point still has a text bullet, so the daily podcast and the read-aloud
  skip diagrams.

### Database
- Migration `20261111150000_ewe_05_diagrams.sql`: `bullets.kind` (`text` or `mermaid`);
  `apply_notes` stores the kind and returns `new_diagrams`; "Recreate as Topic/Project" keeps diagrams.

## 0.1.0 — 2026-11-11

First release.

- Sessions as Topics or Projects (type locked, never deleted, Active/Inactive, Recreate as Topic/Project).
- ChatGPT-style chat: up to 10 photos (camera or gallery) or pasted text per message → short bullet
  notes in one AI call, placed into sections and inline for Topics, appended for Projects.
- Notes tab with sections, "added in the last 7 days" dots, date and origin on tap, edit and delete.
- Linking (RAG, off by default): embeddings per section and per topic, one 3-choice placement call,
  linked points shown in both places, Rebuild / Rebuild all / Switch back, hybrid search.
- Daily revision podcast (off by default): 1 → 2 → 4 … day schedule with a cap, detail fading
  2 revisions per level, fitted into 30 minutes, script by AI, voice by Android text-to-speech,
  prepared at 1:00 AM for offline listening.
- Your own keys for OpenAI, Claude, Gemini or any OpenAI-compatible URL, chosen per task.
- Supabase backend with row-level security; offline copy on the phone.
- Sign in with email or Google; confirmation links open the app.
- Optional small photo copies kept privately on the phone.

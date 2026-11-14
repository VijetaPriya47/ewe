# Changelog

Every release of ewe is listed here, newest first. Install a release from the repository's
**Releases** page; each build installs over the previous one.

## 0.3.0 — 2026-11-14

### Added
- **PDF pages**: the attach button now offers **Photos** or **PDF pages**. Pick a PDF, choose a
  page range (up to 60 pages); the pages are turned into images on the phone and sent 10 per
  message, one after another, so each part sees the notes made from the part before. If a part
  fails, **Try again** sends only the parts that are left.
- **Read-aloud on the lock screen and headphones**: "Listen to notes" now shows a media
  notification and lock-screen controls (play/pause, previous/next section, jump to a section),
  answers headphone buttons, and pauses when headphones are unplugged.
- **Highlights**: tap a point (or a table, code block or diagram label) and pick **yellow** or
  **red**; tap the colour again to clear it. Highlights show everywhere the point appears.
- **Underlined important points**: the notes AI underlines the key points and sections
  (about 1 in 5 points at most). Tap a point and use **U** to add or remove the underline yourself.
- **Code blocks**: code, commands, queries and config in what you upload are kept exactly, in a
  monospaced block with its language and a **Copy** button.
- **Tables**: tables in what you upload, or items compared side by side, become real tables in
  the Notes tab, with a **Copy** button (copies the Markdown).
- Chat replies count tables and code blocks, e.g. "Added 2 points, 1 table and 1 code block".

### Changed
- **The daily podcast is made even without AI**: if there is no podcast model, no internet, or the
  AI call fails, the episode reads the due notes as they are (shortened for older sections) instead
  of not appearing. Night runs wait for the network twice, 10 minutes apart, first. The episode is
  marked **Plain reading** with the reason and a **Try the AI script again** link.
- Read-aloud and the podcast read tables row by row and skip code blocks and diagrams.

### Fixed
- Search results label code blocks and tables like diagrams.

### Database
- Migration `20261114120000_ewe_06_rich_notes.sql`: `bullets.kind` also allows `code` and `table`;
  new `bullets.lang`, `bullets.important`, `bullets.highlight` (`yellow`/`red`) and
  `sections.important`; `apply_notes` stores them and returns `new_tables` and `new_code`;
  "Recreate as Topic/Project" keeps them.

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

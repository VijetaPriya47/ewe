# Changelog

Every release of ewe is listed here, newest first. Install a release from the repository's
**Releases** page; each build installs over the previous one.

## 0.7.0 — 2026-11-14

### Added
- **Reorder sections.** In Notes, tap **Reorder** (next to Listen to notes) or press and hold any
  section title. Drag a section by its handle, or press and hold a row and drag it; the other
  sections slide over to make room. **Done** saves the new order, **Cancel** leaves it as it was.
  A section's points move with it. Read-aloud and the daily podcast follow the new order, and
  new sections from the AI still go at the end. Screen readers get **Move up** / **Move down**
  actions on each row.

## 0.6.0 — 2026-11-14

### Added
- **Figures from your own photos.** In the cropper, **Save as figure** keeps the cut-out as a
  figure (up to 6 per message); the photo itself stays as it was. Figures show in a row above the
  message box, marked **Figure**, and are sent with Notes messages. The AI sees each figure, writes a
  one-line caption, and puts it in the section it belongs to, right after the points it
  illustrates. Tap a figure in Notes to see it full screen (pinch to zoom, double-tap to reset).
- Figures are kept in a private storage folder that only you can read, and on your phone after the
  first view, so they show offline. Deleting a figure deletes its picture too.
- **Captions on diagrams.** The AI gives each diagram a one-line caption, shown under it.
  Edit a diagram to change its caption and code together; edit a figure to change its caption.
- Search finds figures and diagrams by their captions.
- Captions are not read aloud, and figures are left out of read-aloud and the podcast.

### Database
- Migration `20261114200000_ewe_08_figures.sql`: note kind `figure`, a `caption` column, the private
  `figures` storage bucket (5 MB, JPEG/PNG/WebP) with per-user folder policies; `apply_notes`
  accepts figures only from your own folder; captions are used for linking and copied when a
  session is recreated.

## 0.5.0 — 2026-11-14

### Added
- **Crop photos before they are sent.** Each picked photo has a small crop button (or tap the
  thumbnail); a photo you just took with the camera opens the cropper straight away.
  Drag the corners or edges to resize, drag inside to move, **Rotate** turns it 90°, **Reset** starts
  over. **Done** replaces the photo in the message with the cropped one; **Cancel** keeps it as it
  was. Your original photo in the gallery is never changed. Works for notes, Reference and Summary
  messages alike.

## 0.4.0 — 2026-11-14

### Added
- **Four tabs per session: Session · Notes · Overview · References.**
- **References.** Every link in what you send is saved to the session's References tab, once
  (the same page written slightly differently — `www.`, a trailing `/`, `#…`, tracking tags like
  `utm_…` — counts as one link). Each gets a number that never changes. Notes that use a link show a
  small **[n]** chip; tap it for the title, the link, **Open**, **Copy** and **All references**.
  Links you paste are copied exactly by the app; the AI only gives titles and descriptions, and reads
  links that appear in photos. Tap a reference to edit or delete it (notes that cited a deleted one
  show **[?]**). When you edit a point you see and type plain [n] numbers.
- **Message modes: Notes · Reference · Summary**, as chips above the message box. Typing
  `reference` or `summary` as the first word selects it for you (the word itself is not sent).
  - **Reference**: the links and what they are about go to References only; nothing is added to Notes.
  - **Summary**: your own summary, or a photo of a diagram you drew, is redrawn as a diagram in
    Overview; nothing is added to Notes.
  Each message in the chat shows where it went (→ References, → Overview).
- **Overview tab.**
  - **Recall map**: a mind map of the session's section names only, made on the phone (no AI) and
    always up to date — look at the names and try to recall what's under each.
  - **Make a better overview (AI)**: one call to your notes model adds the key sub-topic names under
    each section, still without details; marked "notes changed since" when it gets out of date.
  - **Your summaries**: the summaries you sent, newest first, with date, Copy and delete.
- PDFs can be sent in Reference or Summary mode too.

### Fixed
- **Diagrams that couldn't be drawn.** The notes AI sometimes wrote a whole Mermaid diagram on one
  line (Mermaid needs one statement per line), and it was saved unchecked. Now:
  - the prompt asks for one statement per line and quoted labels, with an example;
  - the server mends one-line flowcharts and unquoted labels before saving;
  - before drawing, the app checks the code with Mermaid and tries the same repairs (plus stray
    ``` fences); a repair that draws is shown at once and saved, so existing broken diagrams mend
    themselves the next time you open them;
  - if nothing works, your notes model is asked once to correct the code ("Fixing this diagram…");
    only if that fails too is the code shown, with a **Fix with AI** button.
  (This fix was first sent as a 0.3.1 build.)
- **"App not installed" when updating.** Builds made here and builds from GitHub Releases had
  different version numbers, so one could count as older than the other. The number now comes from
  the release (0.4.0 → 400) everywhere, so every new release installs over the last one.

### Database and server
- Migration `20261114160000_ewe_07_references_overview.sql`: `session_references` (with
  `ref_url_key` to compare links and stable numbers), `session_summaries`, `messages.mode`,
  `upsert_references`; embeddings ignore citation marks; "Recreate as Topic/Project" copies
  references (same numbers) and summaries.
- Edge functions: `generate-notes` gains the reference and summary modes and saves links;
  new `make-overview` and `fix-diagram`.

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

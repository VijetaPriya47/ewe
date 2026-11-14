# ewe

An Android app that turns what you read into short bullet notes and brings them back
for revision on a spaced schedule. Photograph book pages or handwritten notes, or paste
subtitles, in a chat; ewe keeps concise notes per session, can link related notes across
topics, and can read you a daily revision podcast.

| Feature | Default | Uses AI for |
| --- | --- | --- |
| Notes | Always on | Writing notes from photos / text (one call per upload) |
| Linking (RAG) | Off | Embeddings + a tiny 3-choice placement call |
| Daily podcast | Off | Writing the day's script (speech is Android's built-in engine) |

You bring your own keys (OpenAI, Claude, Gemini, or any OpenAI-compatible URL) and pick a
provider and model per task. Keys stay on the phone and are sent with each request.

## What is in this repository

```
app/                         Android app (Kotlin, Jetpack Compose)
supabase/migrations/         Database schema: tables, rules, row-level security, RAG functions
supabase/functions/
  generate-notes/            Notes, reference and summary modes: prompt, provider call, save
  linking/                   Embeddings: rebuild, placement into Topics, search by meaning
  _shared/                   Provider adapters (OpenAI-style, Claude, Gemini) and prompts
supabase/tests/              SQL tests that run against a local Postgres
.github/workflows/           APK build on every push; one-click Supabase deploy
```

## Set up

### 1. Supabase

The project **ewe** (`rotvvbjfzfkkggpjuvwd`, Mumbai) is already set up from this repository:
the four migrations in `supabase/migrations/` are applied and both functions are deployed.

**Let Supabase deploy from this repository** (so a push to `main` updates the database and functions):

1. Supabase dashboard → project **ewe** → Project Settings → Integrations → **GitHub** → connect
   `VijetaPriya47/ewe`.
2. Working directory: `.` (the folder that contains `supabase/`). Production branch: `main`. Turn on **Deploy to production**.

From then on Supabase reads `supabase/migrations/`, `supabase/functions/` and `supabase/config.toml`
from GitHub on every push to `main`. The migration files here carry the same version numbers as
the ones already applied, so nothing is run twice.

Alternative without the integration: add the repository secrets `SUPABASE_ACCESS_TOKEN`,
`SUPABASE_PROJECT_REF` and `SUPABASE_DB_PASSWORD`, then run **Actions → Deploy Supabase**.

**Sign-in settings (dashboard → Authentication):**

- URL Configuration → Redirect URLs → add `ewe://auth-callback`. Email confirmation links and
  Google sign-in then return to the app instead of `localhost:3000`.
- Optional: Sign In / Providers → Email → turn off "Confirm email" to skip confirmation emails.
- Google sign-in: in Google Cloud Console create an OAuth client of type **Web application** with the
  authorised redirect URI `https://rotvvbjfzfkkggpjuvwd.supabase.co/auth/v1/callback`, then paste its
  client ID and secret under Sign In / Providers → Google and enable it.

### 2. The app

Every push to `main` builds the APK. Each version has its own release on the repository's
**Releases** page (v0.4.0, v0.5.0 …) with its changes and the APK (`ewe-<version>.apk`); open it on
your phone to install. A copy of the current build is also in `release/ewe.apk`. Builds are signed
with the same sideload key and numbered from the version (0.5.0 → 500), so a newer version installs
over an older one.

**Earlier releases (one-time).** Versions before 0.5.0 have no release yet. GitHub only lets their
tags be created from a computer, so from a clone of this repository run:

```bash
git tag v0.1.0 b2c1c22 && git tag v0.2.0 2a0a0c0 && git tag v0.3.0 a8f4621 && git tag v0.4.0 058ab8f && git push origin v0.1.0 v0.2.0 v0.3.0 v0.4.0
```

then open **Actions → "Add earlier releases" → Run workflow**.

The project URL and publishable key are baked in (`gradle.properties`), so the first screen only
asks for an email and password. Then open Settings, add a provider key and pick a **Notes model**
(one that reads images).

## How it works

- **Sessions** are a Topic or a Project, chosen once. The database refuses to change the type or
  delete a session; they can only be Active or Inactive. "Recreate as Topic/Project" copies the
  notes, dates and revision progress into a new session of the other type.
- **Notes**: each upload is one AI call with three labelled parts — instructions, PREVIOUS NOTES
  (context only, old photos are never re-sent) and NEW CONTENT. For Topics the model must pick an
  existing section or NEW, and for each bullet the line it follows or END; a strict JSON schema
  only allows real choices. Projects just append.
- **Photos** are resized to at most 2048 px on the long side before they are sent (vision models
  downscale beyond that anyway) and then dropped. With "Keep small copies" on, an ~800 px / ~50 KB
  copy is kept inside the app's private storage only.
- **Linking**: sections are embedded as child rows and each session as a parent row built from a
  short summary. New notes are matched against Active Topic sections; a cheap model answers
  `ADD_TO_SECTION`, `NEW_SECTION_IN_TOPIC` or `KEEP_IN_CURRENT`. A linked point is one row shown
  in both places. Changing the embedding model stages a rebuild and swaps it in only when every
  row is done, so "Switch back" keeps working until then.
- **Diagrams**: when the material shows a figure or a flow, the notes AI may add a small Mermaid
  diagram as an extra bullet (`kind = 'mermaid'`). The app draws it with the bundled Mermaid
  library (offline) and offers Copy / Code. Text bullets still carry every point.
- **Listen to notes**: any session's notes can be read aloud on the phone (Android text-to-speech,
  no AI), with the current line highlighted, section skipping and speed control, plus lock-screen
  and headphone controls.
- **Rich notes**: besides points and diagrams, the AI keeps code exactly (`kind = 'code'`) and turns
  tables into Markdown tables (`kind = 'table'`), and underlines key points (`important`). You can
  highlight any point yellow or red.
- **PDFs**: chosen pages are rendered to images on the phone and sent 10 per message.
- **References**: links in what you send are saved per session with stable numbers (one row per
  link, compared after tidying) and cited in notes as `[[ref:<id>]]`, shown as [n] chips.
- **Message modes**: Notes, Reference (links only → References) or Summary (your own summary →
  a diagram in Overview), picked with chips or by starting a message with `reference` / `summary`.
- **Overview**: a recall map of section names made on the phone, an optional AI overview with
  sub-topic names, and your own summaries.
- **Podcast without AI**: if the script can't be written, the episode reads the due notes plainly.
- **Search** runs on the phone's offline copy (keywords). When the index is complete, sections
  found by meaning are added below ("hybrid").
- **Podcast**: each batch of notes (a section + the day it was added) has its own schedule:
  1 → 2 → 4 … days, capped (64 by default). Detail fades two revisions per level. The app fits
  everything due into the target length with plain arithmetic, asks the model for one script per
  section with a word budget, and Android's text-to-speech records it for offline listening.
  It is prepared on the phone at 1:00 AM (editable) or on demand.

## Develop

```bash
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # logic tests + renders every screen to app/build/screens/
bash supabase/tests/run_local.sh      # schema tests against a local Postgres
deno test --allow-net --allow-env supabase/functions/_shared/shared_test.ts
```

`LiveSupabaseTest` runs the app's data layer end to end against a real project (sign-in, notes,
inline placement, linking, rebuild and model switch, search by meaning, revision, recreate). It is
skipped unless the `EWE_E2E_*` environment variables described in the file are set.

Changes per release are in [CHANGELOG.md](CHANGELOG.md).

The Inter typeface is bundled under the SIL Open Font License (`app/src/main/INTER-OFL.txt`).
Mermaid is bundled under the MIT License (`app/src/main/assets/mermaid/LICENSE.txt`).

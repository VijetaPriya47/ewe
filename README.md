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
  generate-notes/            Builds the notes prompt, calls your provider, saves notes atomically
  linking/                   Embeddings: rebuild, placement into Topics, search by meaning
  _shared/                   Provider adapters (OpenAI-style, Claude, Gemini) and prompts
supabase/tests/              SQL tests that run against a local Postgres
.github/workflows/           APK build on every push; one-click Supabase deploy
```

## Set up

### 1. Supabase (once)

1. Create a project at supabase.com (the free tier is enough).
2. Set up the database and functions, either way:
   - **From GitHub, no computer needed:** add the repository secrets `SUPABASE_ACCESS_TOKEN`
     (supabase.com → Account → Access tokens), `SUPABASE_PROJECT_REF` (the id in your project URL)
     and `SUPABASE_DB_PASSWORD`, then run **Actions → Deploy Supabase**.
   - **From a computer** with the Supabase CLI:
     ```bash
     supabase link --project-ref <your-project-ref>
     supabase db push
     supabase functions deploy generate-notes
     supabase functions deploy linking
     ```
3. Optional: under Authentication → Sign In / Providers → Email, turn off "Confirm email" so a
   new account can sign in straight away.

### 2. The app

Every push to `main` builds the APK. Download `ewe.apk` from the repository's **Releases** page
(the release named "latest") on your phone and open it to install. Builds are signed with the
same sideload key, so a new build installs over the old one.

On first launch enter your project URL and anon/publishable key (Supabase → Project settings →
API), then create an account. To have them pre-filled, add the repository **variables**
`EWE_SUPABASE_URL` and `EWE_SUPABASE_ANON_KEY` (Settings → Secrets and variables → Actions → Variables).

Then open Settings in the app, add a provider key and pick a **Notes model** (one that reads images).

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

The Inter typeface is bundled under the SIL Open Font License (`app/src/main/INTER-OFL.txt`).

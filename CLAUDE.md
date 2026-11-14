# Notes for working on ewe

- Commits are authored as `VijetaPriya47 <vijeta004@gmail.com>` (set in the repo's git config; the owner asked for this
  on 2026-11-14). Do not override it with another author.

- Every release gets an entry at the top of `CHANGELOG.md` (version, date, Added / Changed / Fixed,
  and any new database migration). Bump `versionName` in `app/build.gradle.kts` to match.
- Supabase deploys from this repo (GitHub integration, branch `main`, working directory `.`):
  new files in `supabase/migrations/` and changes in `supabase/functions/` go live on push.
  Never edit an applied migration; add a new timestamped file.
- Tests: `./gradlew :app:testDebugUnitTest`, `bash supabase/tests/run_local.sh`,
  `deno test --allow-net --allow-env supabase/functions/_shared/shared_test.ts`.

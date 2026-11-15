-- ewe — mark a session as completed.
--   * completed_at: when you marked it completed; null = still in progress. A mark only: the session stays
--     Active or Inactive as it was, revision carries on, and updated_at is not touched, so the list order holds.

alter table public.sessions add column completed_at timestamptz;

-- ewe — notes from audio: the model that turns speech into text (Settings › Notes).
-- Only the choice is stored; keys stay on the phone, and the audio goes from the phone straight to
-- that provider.

alter table public.settings
  add column transcribe_provider text,
  add column transcribe_model    text;

-- Trigger functions: pin the search path and make them uncallable through the API.
alter function public.settings_guard()    set search_path = public;
alter function public.sessions_guard()    set search_path = public;
alter function public.messages_readonly() set search_path = public;
alter function public.touch_updated_at()  set search_path = public;

revoke execute on function public.settings_guard()    from public, anon, authenticated;
revoke execute on function public.sessions_guard()    from public, anon, authenticated;
revoke execute on function public.messages_readonly() from public, anon, authenticated;
revoke execute on function public.touch_updated_at()  from public, anon, authenticated;
revoke execute on function public.bullets_schedule()  from public, anon, authenticated;
revoke execute on function public.sections_prune()    from public, anon, authenticated;

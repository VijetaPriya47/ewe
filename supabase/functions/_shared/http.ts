import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.119.0";
import { ProviderError } from "./providers.ts";

export const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type, apikey, x-client-info",
};

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

export class HttpError extends Error {
  constructor(message: string, public status = 400) { super(message); }
}

/** A database client that acts as the signed-in user, so row-level security applies. */
export function userClient(req: Request): SupabaseClient {
  const auth = req.headers.get("Authorization");
  if (!auth) throw new HttpError("Not signed in", 401);
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export function serve(handler: (req: Request, body: Record<string, unknown>) => Promise<unknown>) {
  Deno.serve(async (req) => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
    if (req.method !== "POST") return json({ error: "POST only" }, 405);
    try {
      const body = await req.json().catch(() => ({}));
      return json(await handler(req, body));
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      if (e instanceof ProviderError) return json({ error: e.message, detail: e.detail }, e.status);
      console.error(e);
      return json({ error: e instanceof Error ? e.message : "Unexpected error" }, 500);
    }
  });
}

/** Throw on a PostgREST error, return the data otherwise. */
export function must<T>(res: { data: T | null; error: { message: string } | null }): T {
  if (res.error) throw new HttpError(res.error.message, 500);
  return res.data as T;
}

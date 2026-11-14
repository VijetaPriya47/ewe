// POST /functions/v1/find-figures — where the figures are on up to 10 PDF pages, so the phone can cut
// them out and show them to you before the notes are made.
//
// Body: { "pages": [{ "mime", "data" }], "provider": ProviderCfg }
//   →   { "figures": [{ "page", "top", "left", "bottom", "right", "label" }] }   (edges 0–1000)
// Nothing is saved. Keys are used for this call only.

import { HttpError, requireUser, serve, userClient } from "../_shared/http.ts";
import { checkCfg, generateJson, type ImageInput } from "../_shared/providers.ts";
import { buildFindRequest, cleanFound } from "../_shared/find_figures.ts";

const MAX_PAGES = 10;

serve(async (req, body) => {
  const provider = checkCfg(body.provider, "notes");
  await requireUser(req, userClient(req));
  const pages = (Array.isArray(body.pages) ? body.pages : []) as ImageInput[];
  if (pages.length === 0) throw new HttpError("pages are required");
  if (pages.length > MAX_PAGES) throw new HttpError(`At most ${MAX_PAGES} pages at a time.`);
  if (pages.some((p) => typeof p?.data !== "string" || !/^image\/(jpeg|png|webp)$/.test(String(p?.mime)))) {
    throw new HttpError("Each page must be a JPEG, PNG or WebP image.");
  }
  const out = await generateJson(provider, buildFindRequest(pages));
  return { figures: cleanFound(out, pages.length) };
});

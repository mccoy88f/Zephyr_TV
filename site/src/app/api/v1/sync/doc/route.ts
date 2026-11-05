import { allow } from "@/server/limit";
import { deviceOf } from "@/server/devices";
import { MAX_DOC, readDoc, validKey, writeDoc } from "@/server/sync";

export const dynamic = "force-dynamic";

/** A document of the account (?key=…): {rev, data} (data a JSON text, null when there's none). */
export async function GET(req: Request) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const key = new URL(req.url).searchParams.get("key") ?? "";
  if (!(await validKey(me.account.id, key))) return new Response(null, { status: 404 });
  return Response.json(await readDoc(me.account.id, key));
}

/**
 * A document written ({base, data}: the revision it was merged with, and a JSON text): 200 {rev}; 409 {rev, data}
 * when it changed since [base] (merge with that, write again); 413 when too large.
 */
export async function PUT(req: Request) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  if (!allow(`sync:${me.device.id}`, 300, 3_600_000)) return new Response(null, { status: 429 });
  const key = new URL(req.url).searchParams.get("key") ?? "";
  if (!(await validKey(me.account.id, key))) return new Response(null, { status: 404 });
  const text = await req.text();
  if (text.length > MAX_DOC + 1000) return new Response(null, { status: 413 });
  const body = (() => { try { return JSON.parse(text); } catch { return null; } })();
  if (!body || typeof body.data !== "string" || !Number.isInteger(body.base) || body.base < 0) return new Response(null, { status: 400 });
  try {
    JSON.parse(body.data);
  } catch {
    return new Response(null, { status: 400 });
  }
  const rev = await writeDoc(me.account.id, me.device.id, key, body.base, body.data);
  if (rev === null) return Response.json(await readDoc(me.account.id, key), { status: 409 });
  return Response.json({ rev });
}

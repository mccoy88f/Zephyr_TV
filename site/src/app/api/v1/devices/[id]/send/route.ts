import { and, eq } from "drizzle-orm";
import { db } from "@/db";
import { devices } from "@/db/schema";
import { deviceOf } from "@/server/devices";
import { MAX_HANDOFF, send } from "@/server/handoffs";
import { allow } from "@/server/limit";

export const dynamic = "force-dynamic";
type Ctx = { params: Promise<{ id: string }> };
const UUID = /^[0-9a-f-]{36}$/;

/**
 * "Send to": the body (the app's JSON: the title, the stream, where it got to) for another device of the account,
 * which plays it there. 404 when the device isn't the account's (or is this one), 413 when it's too big.
 */
export async function POST(req: Request, { params }: Ctx) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const { id } = await params;
  if (!UUID.test(id) || id === me.device.id) return new Response(null, { status: 404 });
  if (!allow(`send:${me.device.id}`, 20, 60_000)) return new Response(null, { status: 429 });
  const text = await req.text();
  if (text.length > MAX_HANDOFF) return new Response(null, { status: 413 });
  try {
    if (typeof JSON.parse(text) !== "object") throw new Error();
  } catch {
    return new Response(null, { status: 400 });
  }
  const [to] = await db.select({ id: devices.id }).from(devices).where(and(eq(devices.id, id), eq(devices.accountId, me.account.id)));
  if (!to) return new Response(null, { status: 404 });
  const sent = await send(me.account.id, me.device.id, id, text);
  return Response.json({ id: sent });
}

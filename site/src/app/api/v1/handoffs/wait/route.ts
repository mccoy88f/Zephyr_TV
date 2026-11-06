import { inArray } from "drizzle-orm";
import { db } from "@/db";
import { devices } from "@/db/schema";
import { deviceOf } from "@/server/devices";
import { wait } from "@/server/handoffs";

export const dynamic = "force-dynamic";

/** How long a wait stays open with nothing sent: under the proxies' minute. */
const WAIT_MS = 25_000;

/**
 * What another device of the account sent this one (its token as Bearer), as soon as it's sent: {handoffs: [{id,
 * from, data}]}, `from` its device's name, `data` the sender's JSON; 204 after a while with nothing. Waiting also
 * tells the account's devices that this one is on (see devices).
 */
export async function GET(req: Request) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const got = await wait(me.device.id, WAIT_MS, req.signal);
  if (!got.length) return new Response(null, { status: 204 });
  const ids = got.map((h) => h.from).filter((f): f is string => !!f);
  const names = ids.length ? new Map((await db.select({ id: devices.id, name: devices.name }).from(devices).where(inArray(devices.id, ids))).map((d) => [d.id, d.name])) : new Map();
  return Response.json({
    handoffs: got.map((h) => ({ id: h.id, from: (h.from && names.get(h.from)) || "", data: JSON.parse(h.data!) })),
  });
}

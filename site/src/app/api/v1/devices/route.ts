import { asc, eq } from "drizzle-orm";
import { db } from "@/db";
import { devices } from "@/db/schema";
import { deviceOf } from "@/server/devices";
import { online } from "@/server/handoffs";

export const dynamic = "force-dynamic";

/** The account's devices (the asking one's token as Bearer): each one's name and model, whether it's on, which is this one. */
export async function GET(req: Request) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const all = await db.select().from(devices).where(eq(devices.accountId, me.account.id)).orderBy(asc(devices.name));
  return Response.json({
    devices: all.map((d) => ({ id: d.id, name: d.name, model: d.model, online: d.id === me.device.id || online(d.id), me: d.id === me.device.id })),
  });
}

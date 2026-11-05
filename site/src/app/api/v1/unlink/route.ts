import { eq } from "drizzle-orm";
import { db } from "@/db";
import { devices } from "@/db/schema";
import { deviceOf } from "@/server/devices";

export const dynamic = "force-dynamic";

/** The TV unlinked by itself (its token as Bearer): the token stops working. */
export async function POST(req: Request) {
  const me = await deviceOf(req);
  if (me) await db.delete(devices).where(eq(devices.id, me.device.id));
  return new Response(null, { status: 204 });
}

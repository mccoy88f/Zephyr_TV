import { asc, eq } from "drizzle-orm";
import { db } from "@/db";
import { profiles } from "@/db/schema";
import { deviceOf } from "@/server/devices";

export const dynamic = "force-dynamic";

/** The linked TV's account (its token as Bearer): its email, this TV's name, and the account's Zephyr TV users. 401 when unlinked. */
export async function GET(req: Request) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const users = await db.select().from(profiles).where(eq(profiles.accountId, me.account.id)).orderBy(asc(profiles.position), asc(profiles.createdAt));
  return Response.json({
    email: me.account.email,
    device: { id: me.device.id, name: me.device.name },
    users: users.map((u) => ({ id: u.id, name: u.name, avatar: u.avatar, updated: u.updatedAt.toISOString() })),
  });
}

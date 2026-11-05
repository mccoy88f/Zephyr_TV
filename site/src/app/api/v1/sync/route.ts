import { asc, eq } from "drizzle-orm";
import { db } from "@/db";
import { profiles } from "@/db/schema";
import { deviceOf } from "@/server/devices";
import { revisions } from "@/server/sync";

export const dynamic = "force-dynamic";

/** Where the account stands, for a linked TV (Bearer): its users, and its documents' revisions. 401 when unlinked. */
export async function GET(req: Request) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const users = await db.select().from(profiles).where(eq(profiles.accountId, me.account.id)).orderBy(asc(profiles.position), asc(profiles.createdAt));
  return Response.json({
    email: me.account.email,
    users: users.map((u) => ({ id: u.id, name: u.name, avatar: u.avatar })),
    docs: await revisions(me.account.id),
  });
}

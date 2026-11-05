import { count, eq } from "drizzle-orm";
import { db } from "@/db";
import { profiles } from "@/db/schema";
import { parseAvatar } from "@/lib/avatars";
import { deviceOf } from "@/server/devices";

export const dynamic = "force-dynamic";

/** A Zephyr TV user added from a TV ({name, avatar}): {id}. 409 at 12 users. */
export async function POST(req: Request) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const body = await req.json().catch(() => ({}));
  const name = typeof body?.name === "string" ? body.name.trim().slice(0, 40) : "";
  if (!name) return new Response(null, { status: 400 });
  const avatar = parseAvatar(body?.avatar) ? (body.avatar as string) : null;
  const [n] = await db.select({ n: count() }).from(profiles).where(eq(profiles.accountId, me.account.id));
  if (n.n >= 12) return new Response(null, { status: 409 });
  const [p] = await db.insert(profiles).values({ accountId: me.account.id, name, avatar, position: n.n }).returning({ id: profiles.id });
  return Response.json({ id: p.id });
}

import { and, eq } from "drizzle-orm";
import { db } from "@/db";
import { profiles } from "@/db/schema";
import { parseAvatar } from "@/lib/avatars";
import { deviceOf } from "@/server/devices";
import { dropUserDocs } from "@/server/sync";

export const dynamic = "force-dynamic";
type Ctx = { params: Promise<{ id: string }> };
const UUID = /^[0-9a-f-]{36}$/;

/** A user's name or avatar changed on a TV ({name?, avatar?}: avatar null for none); POST too (Android has no PATCH). 404 when not the account's. */
export async function PATCH(req: Request, { params }: Ctx) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const { id } = await params;
  if (!UUID.test(id)) return new Response(null, { status: 404 });
  const body = await req.json().catch(() => ({}));
  const set: { name?: string; avatar?: string | null; updatedAt: Date } = { updatedAt: new Date() };
  if (typeof body?.name === "string" && body.name.trim()) set.name = body.name.trim().slice(0, 40);
  if ("avatar" in (body ?? {})) set.avatar = parseAvatar(body.avatar) ? body.avatar : null;
  const done = await db.update(profiles).set(set).where(and(eq(profiles.id, id), eq(profiles.accountId, me.account.id))).returning({ id: profiles.id });
  return new Response(null, { status: done.length ? 204 : 404 });
}

/** A user removed on a TV: gone from the account, with what was synced of theirs. */
export async function DELETE(req: Request, { params }: Ctx) {
  const me = await deviceOf(req);
  if (!me) return new Response(null, { status: 401 });
  const { id } = await params;
  if (UUID.test(id)) {
    await db.delete(profiles).where(and(eq(profiles.id, id), eq(profiles.accountId, me.account.id)));
    await dropUserDocs(me.account.id, id);
  }
  return new Response(null, { status: 204 });
}
export { PATCH as POST };

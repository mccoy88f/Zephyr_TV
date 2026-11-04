import { eq } from "drizzle-orm";
import { db } from "@/db";
import { traktSignins } from "@/db/schema";
import { allow } from "@/server/limit";
import { STATE } from "@/server/trakt";

export const dynamic = "force-dynamic";

/**
 * The TV asking for its sign-in ({state}): 200 with Trakt's tokens once the phone is done (given once: the row goes),
 * 202 while it waits, 410 when refused, 404 when unknown or too old, 429 when asked too often. As the site before it
 * answered, so Zephyr TV reads both alike.
 */
export async function POST(req: Request) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`poll:${ip}`, 120, 10 * 60_000)) return new Response(null, { status: 429 });
  const state = (await req.json().catch(() => ({})))?.state;
  if (typeof state !== "string" || !STATE.test(state)) return new Response(null, { status: 404 });
  const [row] = await db.select().from(traktSignins).where(eq(traktSignins.state, state));
  if (!row) return new Response(null, { status: 404 });
  if (row.status === "waiting") return new Response(null, { status: 202 });
  await db.delete(traktSignins).where(eq(traktSignins.state, state));
  if (row.status === "refused") return new Response(null, { status: 410 });
  return Response.json(row.tokens);
}

import { eq } from "drizzle-orm";
import { db } from "@/db";
import { traktSignins } from "@/db/schema";
import { allow } from "@/server/limit";
import { STATE, trakt } from "@/server/trakt";

export const dynamic = "force-dynamic";

/**
 * The TV asking for its sign-in ({state}): 200 with Trakt's code and the redirect it came back to, once the phone is
 * done (given once: the row goes; the TV swaps it for the tokens with its PKCE verifier), 202 while it waits, 410 when
 * refused, 404 when unknown or too old, 429 when asked too often.
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
  if (row.status === "refused" || !row.code) return new Response(null, { status: 410 });
  return Response.json({ code: row.code, redirect_uri: trakt.redirect });
}

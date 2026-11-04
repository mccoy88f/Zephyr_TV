import { eq } from "drizzle-orm";
import { db } from "@/db";
import { traktSignins } from "@/db/schema";
import { allow } from "@/server/limit";
import { CHALLENGE, STATE, dropOldSignins, trakt } from "@/server/trakt";

export const dynamic = "force-dynamic";

/** The page the phone ends on: the sign-in done, refused, or gone wrong (see traktauth/done). */
function done(req: Request, result: "ok" | "refused" | "error") {
  return Response.redirect(new URL(`/traktauth/done?r=${result}`, process.env.SITE_URL || req.url), 302);
}

/**
 * A Trakt sign-in, from the TV's QR code to Trakt and back (the address Zephyr TV puts in the code), with PKCE.
 * ?start=<state>&cc=<challenge>: kept, then on to Trakt's page to sign in and allow Zephyr TV, with the TV's challenge.
 * ?code=…&state=…: Trakt's code, kept for the TV (see api/trakt/poll), which swaps it for the tokens itself with its
 *   verifier: the tokens never pass through here.
 * ?error=access_denied&state=…: refused on Trakt's page.
 */
export async function GET(req: Request) {
  const q = new URL(req.url).searchParams;
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`traktauth:${ip}`, 30, 10 * 60_000)) return new Response("Too many tries", { status: 429 });

  const start = q.get("start");
  if (start) {
    const challenge = q.get("cc");
    if (!STATE.test(start) || !challenge || !CHALLENGE.test(challenge)) return new Response("Bad sign-in", { status: 400 });
    await dropOldSignins();
    await db.insert(traktSignins).values({ state: start }).onConflictDoNothing();
    const to = new URL("https://trakt.tv/oauth/authorize");
    to.search = new URLSearchParams({
      response_type: "code", client_id: trakt.clientId, redirect_uri: trakt.redirect, state: start,
      code_challenge: challenge, code_challenge_method: "S256",
    }).toString();
    return Response.redirect(to, 302);
  }

  const state = q.get("state");
  if (!state || !STATE.test(state)) return new Response("Bad state", { status: 400 });
  const [row] = await db.select().from(traktSignins).where(eq(traktSignins.state, state));
  if (!row || row.status !== "waiting") return done(req, "error"); // too old, or used already

  const code = q.get("code");
  if (q.get("error") || !code) {
    await db.update(traktSignins).set({ status: "refused" }).where(eq(traktSignins.state, state));
    return done(req, q.get("error") ? "refused" : "error");
  }
  await db.update(traktSignins).set({ status: "done", code: code.slice(0, 512) }).where(eq(traktSignins.state, state));
  return done(req, "ok");
}

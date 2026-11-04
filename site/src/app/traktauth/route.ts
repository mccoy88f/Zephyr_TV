import { eq } from "drizzle-orm";
import { db } from "@/db";
import { traktSignins } from "@/db/schema";
import { allow } from "@/server/limit";
import { STATE, dropOldSignins, trakt, traktToken } from "@/server/trakt";

export const dynamic = "force-dynamic";

/** The page the phone ends on: the sign-in done, refused, or gone wrong (see traktauth/done). */
function done(req: Request, result: "ok" | "refused" | "error") {
  return Response.redirect(new URL(`/traktauth/done?r=${result}`, process.env.SITE_URL || req.url), 302);
}

/**
 * A Trakt sign-in, from the TV's QR code to Trakt and back (the address Zephyr TV puts in the code).
 * ?start=<state>: kept, then on to Trakt's page to sign in and allow Zephyr TV.
 * ?code=…&state=…: Trakt's answer, swapped for the tokens (with the app's secret), kept for the TV (see api/trakt/poll).
 * ?error=access_denied&state=…: refused on Trakt's page.
 */
export async function GET(req: Request) {
  const q = new URL(req.url).searchParams;
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`traktauth:${ip}`, 30, 10 * 60_000)) return new Response("Too many tries", { status: 429 });
  if (!trakt.secret) return new Response("Trakt isn't set up on this site (TRAKT_CLIENT_SECRET)", { status: 503 });

  const start = q.get("start");
  if (start) {
    if (!STATE.test(start)) return new Response("Bad state", { status: 400 });
    await dropOldSignins();
    await db.insert(traktSignins).values({ state: start }).onConflictDoNothing();
    const to = new URL("https://trakt.tv/oauth/authorize");
    to.search = new URLSearchParams({ response_type: "code", client_id: trakt.clientId, redirect_uri: trakt.redirect, state: start }).toString();
    return Response.redirect(to, 302);
  }

  const state = q.get("state");
  if (!state || !STATE.test(state)) return new Response("Bad state", { status: 400 });
  const [row] = await db.select().from(traktSignins).where(eq(traktSignins.state, state));
  if (!row || row.status !== "waiting") return done(req, "error"); // too old, or used already

  if (q.get("error")) {
    await db.update(traktSignins).set({ status: "refused" }).where(eq(traktSignins.state, state));
    return done(req, "refused");
  }
  const code = q.get("code");
  if (!code) return new Response("No code", { status: 400 });
  const { status, json } = await traktToken({ code, grant_type: "authorization_code" });
  if (status !== 200) {
    console.warn(`trakt: code refused (${status})`);
    await db.update(traktSignins).set({ status: "refused" }).where(eq(traktSignins.state, state));
    return done(req, "error");
  }
  await db.update(traktSignins).set({ status: "done", tokens: json }).where(eq(traktSignins.state, state));
  return done(req, "ok");
}

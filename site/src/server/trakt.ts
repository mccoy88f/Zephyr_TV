import "server-only";
import { lt } from "drizzle-orm";
import { db } from "@/db";
import { traktSignins } from "@/db/schema";

/**
 * Zephyr TV's app on Trakt: its id is public (it's in the app too), its secret only here, as Trakt wants it to give
 * and refresh every token (an app's own copy would be anyone's). Sign-ins come back to [redirect], which must be among
 * the app's redirect URIs on trakt.tv.
 */
export const trakt = {
  clientId: process.env.TRAKT_CLIENT_ID || "0TPZx8EN9N4gVqDp4QDbegDyKkcgLO9BDC1lavq9WWY",
  secret: process.env.TRAKT_CLIENT_SECRET || "",
  redirect: `${process.env.SITE_URL || "https://ztv.mccoy88f.link"}/traktauth`,
};

/** A sign-in's state as the TV makes it (24 random bytes, base64url: 32 characters); anything else isn't one. */
export const STATE = /^[A-Za-z0-9_-]{16,64}$/;

/** How long a sign-in waits: for the phone, then for the TV to take its tokens. */
export const SIGNIN_MS = 15 * 60_000;

/** Sign-ins older than SIGNIN_MS gone (tokens a TV never took among them). */
export async function dropOldSignins() {
  await db.delete(traktSignins).where(lt(traktSignins.createdAt, new Date(Date.now() - SIGNIN_MS)));
}

/** Trakt's token endpoint, with the app's secret: [body] is the grant (a code, or a refresh token). Its status and answer as they are. */
export async function traktToken(body: Record<string, string>): Promise<{ status: number; json: unknown }> {
  const res = await fetch("https://api.trakt.tv/oauth/token", {
    method: "POST",
    headers: { "Content-Type": "application/json", "User-Agent": "ztv-site" },
    body: JSON.stringify({ client_id: trakt.clientId, client_secret: trakt.secret, redirect_uri: trakt.redirect, ...body }),
    cache: "no-store",
  });
  return { status: res.status, json: await res.json().catch(() => ({})) };
}

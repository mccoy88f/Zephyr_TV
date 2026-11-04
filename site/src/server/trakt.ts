import "server-only";
import { lt } from "drizzle-orm";
import { db } from "@/db";
import { traktSignins } from "@/db/schema";

/**
 * Zephyr TV's app on Trakt, signed in to with PKCE: no secret anywhere (Trakt no longer wants one). Its id is public
 * (it's in Zephyr TV too); TRAKT_CLIENT_ID only to use another. Sign-ins come back to [redirect], which must be among
 * the app's redirect URIs on trakt.tv.
 */
export const trakt = {
  clientId: process.env.TRAKT_CLIENT_ID || "0TPZx8EN9N4gVqDp4QDbegDyKkcgLO9BDC1lavq9WWY",
  redirect: `${process.env.SITE_URL || "https://ztv.mccoy88f.link"}/traktauth`,
};

/** A sign-in's state as the TV makes it (24 random bytes, base64url: 32 characters); anything else isn't one. */
export const STATE = /^[A-Za-z0-9_-]{16,64}$/;

/** A PKCE challenge (S256): the SHA-256 of the TV's verifier, base64url without padding (43 characters). */
export const CHALLENGE = /^[A-Za-z0-9_-]{43}$/;

/** How long a sign-in waits: for the phone, then for the TV to take its code. */
export const SIGNIN_MS = 15 * 60_000;

/** Sign-ins older than SIGNIN_MS gone (codes a TV never took among them). */
export async function dropOldSignins() {
  await db.delete(traktSignins).where(lt(traktSignins.createdAt, new Date(Date.now() - SIGNIN_MS)));
}

import "server-only";
import { createHash, randomBytes } from "node:crypto";
import { and, eq, gt } from "drizzle-orm";
import { cookies, headers } from "next/headers";
import { db } from "@/db";
import { type Account, accounts, sessions } from "@/db/schema";

const COOKIE = "ztv_session";
const DAYS = 30;

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

/** A new session for [accountId]: its token in a cookie the page's scripts can't read, its hash in the database. */
export async function startSession(accountId: string) {
  const token = randomBytes(32).toString("base64url");
  const expiresAt = new Date(Date.now() + DAYS * 86_400_000);
  const userAgent = (await headers()).get("user-agent")?.slice(0, 200) ?? null;
  await db.insert(sessions).values({ tokenHash: sha256(token), accountId, userAgent, expiresAt });
  (await cookies()).set(COOKIE, token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    expires: expiresAt,
  });
}

/** The account signed in on this browser, or null: none, expired, or blocked. */
export async function currentAccount(): Promise<Account | null> {
  const token = (await cookies()).get(COOKIE)?.value;
  if (!token) return null;
  const [row] = await db
    .select({ account: accounts })
    .from(sessions)
    .innerJoin(accounts, eq(sessions.accountId, accounts.id))
    .where(and(eq(sessions.tokenHash, sha256(token)), gt(sessions.expiresAt, new Date())));
  return row && !row.account.blockedAt ? row.account : null;
}

/** This browser signed out: its session gone from the database, its cookie cleared. */
export async function endSession() {
  const jar = await cookies();
  const token = jar.get(COOKIE)?.value;
  if (token) await db.delete(sessions).where(eq(sessions.tokenHash, sha256(token)));
  jar.delete(COOKIE);
}

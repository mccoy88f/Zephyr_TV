import "server-only";
import { createHash, randomBytes, randomInt } from "node:crypto";
import { and, eq, lt } from "drizzle-orm";
import { db } from "@/db";
import { type Account, accounts, devices, linkCodes } from "@/db/schema";

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

/** How long a TV's code works. */
export const LINK_MINUTES = 10;

/** A code's letters: no 0/O, 1/I/L, so it's typed right from a TV. */
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

/** "ABCD-EFGH" from what was typed ("abcd efgh", "ABCDEFGH"), or null when it can't be one. */
export function normalizeCode(typed: string): string | null {
  const c = typed.toUpperCase().replace(/[^A-Z0-9]/g, "");
  return c.length === 8 && [...c].every((ch) => ALPHABET.includes(ch)) ? `${c.slice(0, 4)}-${c.slice(4)}` : null;
}

const clip = (s: unknown, n: number) => (typeof s === "string" ? s.trim().slice(0, n) : "") || null;

/**
 * A TV asking to be linked ([name], [model], [version] as it says): its short code to show, and the long one it keeps
 * to ask with (see pollLink). Codes past their time go first.
 */
export async function startLink(name: unknown, model: unknown, version: unknown) {
  await db.delete(linkCodes).where(lt(linkCodes.expiresAt, new Date()));
  const deviceCode = randomBytes(32).toString("base64url");
  for (let tries = 0; tries < 5; tries++) {
    const raw = Array.from({ length: 8 }, () => ALPHABET[randomInt(ALPHABET.length)]).join("");
    const userCode = `${raw.slice(0, 4)}-${raw.slice(4)}`;
    const inserted = await db.insert(linkCodes).values({
      userCode, deviceCodeHash: sha256(deviceCode),
      name: clip(name, 60) ?? "Zephyr TV", model: clip(model, 80), appVersion: clip(version, 40),
      expiresAt: new Date(Date.now() + LINK_MINUTES * 60_000),
    }).onConflictDoNothing().returning();
    if (inserted.length) return { deviceCode, userCode };
  }
  throw new Error("no free code");
}

/** The TV's link as it stands, asked with its long code: waiting, refused or unknown, or allowed with a new device token (once). */
export async function pollLink(deviceCode: string): Promise<
  { state: "pending" } | { state: "denied" } | { state: "gone" } | { state: "approved"; token: string; account: Account }
> {
  if (typeof deviceCode !== "string" || deviceCode.length < 20 || deviceCode.length > 100) return { state: "gone" };
  const [row] = await db.select().from(linkCodes).where(eq(linkCodes.deviceCodeHash, sha256(deviceCode)));
  if (!row || row.expiresAt < new Date()) return { state: "gone" };
  if (row.status === "pending") return { state: "pending" };
  // Used once: of two asking at the same time, only the one that takes it away goes on.
  const taken = await db.delete(linkCodes).where(eq(linkCodes.userCode, row.userCode)).returning();
  if (!taken.length) return { state: "gone" };
  if (row.status === "denied" || !row.accountId) return { state: "denied" };
  const [account] = await db.select().from(accounts).where(eq(accounts.id, row.accountId));
  if (!account || account.blockedAt) return { state: "denied" };
  const token = randomBytes(32).toString("base64url");
  await db.insert(devices).values({ accountId: account.id, tokenHash: sha256(token), name: row.name, model: row.model, appVersion: row.appVersion });
  return { state: "approved", token, account };
}

/** The TV waiting with [userCode], if it's still waiting. */
export async function pendingLink(userCode: string) {
  const [row] = await db.select().from(linkCodes).where(and(eq(linkCodes.userCode, userCode), eq(linkCodes.status, "pending")));
  return row && row.expiresAt > new Date() ? row : null;
}

/** [userCode]'s TV allowed for [accountId], or refused. */
export async function answerLink(userCode: string, accountId: string, allow: boolean) {
  await db.update(linkCodes).set(allow ? { status: "approved", accountId } : { status: "denied" })
    .where(and(eq(linkCodes.userCode, userCode), eq(linkCodes.status, "pending")));
}

/**
 * The TV asking ([req]'s Bearer token) and its account, or null: unknown token, or its account blocked. Its last time
 * seen moves on (at most every five minutes).
 */
export async function deviceOf(req: Request) {
  const token = req.headers.get("authorization")?.match(/^Bearer ([A-Za-z0-9_-]{20,100})$/)?.[1];
  if (!token) return null;
  const [row] = await db.select({ device: devices, account: accounts }).from(devices)
    .innerJoin(accounts, eq(devices.accountId, accounts.id)).where(eq(devices.tokenHash, sha256(token)));
  if (!row || row.account.blockedAt) return null;
  if (Date.now() - row.device.lastSeenAt.getTime() > 5 * 60_000) {
    const version = clip(req.headers.get("x-zephyr-version"), 40);
    await db.update(devices).set({ lastSeenAt: new Date(), ...(version ? { appVersion: version } : {}) }).where(eq(devices.id, row.device.id));
  }
  return row;
}

import "server-only";
import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { and, asc, eq, like, sql } from "drizzle-orm";
import { db } from "@/db";
import { profiles, syncDocs } from "@/db/schema";

/** The most a document may hold: 5000 watched marks fit well within it. */
export const MAX_DOC = 3 * 1024 * 1024;

/** A document's key: the account's settings, or a user's watched titles or progress (their profile's id). */
const KEY = /^(settings|(watched|progress):[0-9a-f-]{36})$/;

/** [key] if it's one the account may keep: a user's, only of its own users. */
export async function validKey(accountId: string, key: string) {
  if (!KEY.test(key)) return false;
  if (key === "settings") return true;
  const [p] = await db.select({ id: profiles.id }).from(profiles).where(and(eq(profiles.id, key.split(":")[1]), eq(profiles.accountId, accountId)));
  return !!p;
}

// Encrypted at rest when SYNC_KEY is set (AES-256-GCM): a copy of the database alone doesn't tell what's in them.
const secret = () => (process.env.SYNC_KEY ? createHash("sha256").update(process.env.SYNC_KEY).digest() : null);

export function seal(text: string) {
  const key = secret();
  if (!key) return text;
  const iv = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", key, iv);
  const body = Buffer.concat([c.update(text, "utf8"), c.final()]);
  return `v1:${Buffer.concat([iv, c.getAuthTag(), body]).toString("base64")}`;
}

export function open(stored: string): string | null {
  if (!stored.startsWith("v1:")) return stored;
  const key = secret();
  if (!key) return null;
  try {
    const raw = Buffer.from(stored.slice(3), "base64");
    const d = createDecipheriv("aes-256-gcm", key, raw.subarray(0, 12));
    d.setAuthTag(raw.subarray(12, 28));
    return Buffer.concat([d.update(raw.subarray(28)), d.final()]).toString("utf8");
  } catch {
    return null; // SYNC_KEY changed: as if never written (the TVs write it again)
  }
}

/** The account's documents and their revisions. */
export async function revisions(accountId: string) {
  const rows = await db.select({ key: syncDocs.key, rev: syncDocs.rev }).from(syncDocs).where(eq(syncDocs.accountId, accountId));
  return Object.fromEntries(rows.map((r) => [r.key, r.rev]));
}

/** [key]'s document: its revision and what it holds (0 and null when there's none yet). */
export async function readDoc(accountId: string, key: string) {
  const [row] = await db.select().from(syncDocs).where(and(eq(syncDocs.accountId, accountId), eq(syncDocs.key, key)));
  if (!row) return { rev: 0, data: null };
  const data = open(row.data);
  return { rev: data === null ? 0 : row.rev, data };
}

/**
 * [data] written as [key]'s next revision, if [base] is still its revision (0: none yet); else null (it changed
 * meanwhile: read it again, merge, write again).
 */
export async function writeDoc(accountId: string, deviceId: string, key: string, base: number, data: string): Promise<number | null> {
  const sealed = seal(data);
  const now = new Date();
  const updated = await db.update(syncDocs).set({ rev: sql`${syncDocs.rev} + 1`, data: sealed, deviceId, updatedAt: now })
    .where(and(eq(syncDocs.accountId, accountId), eq(syncDocs.key, key), eq(syncDocs.rev, base))).returning({ rev: syncDocs.rev });
  if (updated.length) return updated[0].rev;
  const [row] = await db.select().from(syncDocs).where(and(eq(syncDocs.accountId, accountId), eq(syncDocs.key, key)));
  if (row && open(row.data) !== null) return null; // changed meanwhile
  if (row) {
    // Unreadable (SYNC_KEY changed): written over.
    const over = await db.update(syncDocs).set({ rev: row.rev + 1, data: sealed, deviceId, updatedAt: now })
      .where(and(eq(syncDocs.accountId, accountId), eq(syncDocs.key, key), eq(syncDocs.rev, row.rev))).returning({ rev: syncDocs.rev });
    return over[0]?.rev ?? null;
  }
  const inserted = await db.insert(syncDocs).values({ accountId, key, rev: 1, data: sealed, deviceId, updatedAt: now })
    .onConflictDoNothing().returning({ rev: syncDocs.rev });
  return inserted[0]?.rev ?? null;
}

/** A user's documents, gone with them. */
export async function dropUserDocs(accountId: string, profileId: string) {
  await db.delete(syncDocs).where(and(eq(syncDocs.accountId, accountId), like(syncDocs.key, `%:${profileId}`)));
}

/** Whether [id] is the account's main user: its first, whose TV owns sync. It stays. */
export async function isMain(accountId: string, id: string) {
  const [first] = await db.select({ id: profiles.id }).from(profiles).where(eq(profiles.accountId, accountId))
    .orderBy(asc(profiles.position), asc(profiles.createdAt)).limit(1);
  return first?.id === id;
}

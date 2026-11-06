import "server-only";
import { eq, lt } from "drizzle-orm";
import { db } from "@/db";
import { handoffs } from "@/db/schema";
import { open, seal } from "@/server/sync";

// "Send to" between an account's devices. A device that's on keeps a request open (wait), which this answers as soon
// as something is sent to it, or after a while with nothing: so it gets it within a second, and asking also says it's
// on. Who waits, and who was seen, is kept in memory: enough for one server (as limit.ts).

/** The most one send may hold: a title, its stream and its art's address. */
export const MAX_HANDOFF = 32 * 1024;

/** How long a send waits for its device: then gone (it wasn't on, after all). */
const KEEP_MS = 2 * 60_000;

/** A device seen waiting this recently is on, between two waits. */
const ON_MS = 45_000;

const waiters = new Map<string, Set<() => void>>();
const seen = new Map<string, number>();

/** Whether [deviceId] is on: waiting now, or a moment ago. */
export function online(deviceId: string) {
  return (waiters.get(deviceId)?.size ?? 0) > 0 || Date.now() - (seen.get(deviceId) ?? 0) < ON_MS;
}

/** [data] (the app's JSON, as text) for [to], from [from]: kept, and its device woken if it waits. */
export async function send(accountId: string, from: string, to: string, data: string) {
  await db.delete(handoffs).where(lt(handoffs.createdAt, new Date(Date.now() - KEEP_MS)));
  const [row] = await db.insert(handoffs).values({ accountId, fromDevice: from, toDevice: to, data: seal(data) }).returning({ id: handoffs.id });
  waiters.get(to)?.forEach((wake) => wake());
  return row.id;
}

/** What waits for [deviceId], taken (gone from the table), oldest first; the too old left out. */
async function take(deviceId: string) {
  const rows = await db.delete(handoffs).where(eq(handoffs.toDevice, deviceId)).returning();
  const fresh = Date.now() - KEEP_MS;
  return rows.filter((r) => r.createdAt.getTime() > fresh).sort((a, b) => a.createdAt.getTime() - b.createdAt.getTime())
    .map((r) => ({ id: r.id, from: r.fromDevice, data: open(r.data) })).filter((r) => r.data != null);
}

/**
 * What's sent to [deviceId]: at once when something waits, else when something comes, else nothing after [ms]
 * (or when the request goes away: [signal]).
 */
export async function wait(deviceId: string, ms: number, signal?: AbortSignal) {
  seen.set(deviceId, Date.now());
  const now = await take(deviceId);
  if (now.length) return now;
  await new Promise<void>((done) => {
    const set = waiters.get(deviceId) ?? new Set<() => void>();
    waiters.set(deviceId, set);
    let timer: ReturnType<typeof setTimeout>;
    const end = () => {
      clearTimeout(timer);
      set.delete(end);
      if (set.size === 0) waiters.delete(deviceId);
      signal?.removeEventListener("abort", end);
      done();
    };
    timer = setTimeout(end, ms);
    set.add(end);
    signal?.addEventListener("abort", end);
  });
  seen.set(deviceId, Date.now());
  return take(deviceId);
}


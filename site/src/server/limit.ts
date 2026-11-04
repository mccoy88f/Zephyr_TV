import "server-only";

// Tries counted per key (an address, an email) in memory, over a window: enough for one server. Many servers would
// keep them in the database instead.
const tries = new Map<string, { count: number; until: number }>();

/** Whether one more try on [key] is allowed: at most [max] in [windowMs]. Counts this one. */
export function allow(key: string, max: number, windowMs: number): boolean {
  const now = Date.now();
  const t = tries.get(key);
  if (!t || t.until < now) {
    tries.set(key, { count: 1, until: now + windowMs });
    if (tries.size > 10_000) for (const [k, v] of tries) if (v.until < now) tries.delete(k);
    return true;
  }
  t.count++;
  return t.count <= max;
}

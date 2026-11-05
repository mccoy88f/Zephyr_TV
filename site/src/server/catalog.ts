import "server-only";
import { lookup } from "node:dns/promises";
import { BlockList, isIP } from "node:net";
import { and, eq, notInArray } from "drizzle-orm";
import { db } from "@/db";
import { catalog } from "@/db/schema";

/** The names Zephyr TV publishes with (see the app's Extensions.kt). */
export const PUBLISHED = "manifest-zephyrtv.json";
export const REPOSITORY = "repository-zephyrtv.json";
/** The newest standard Zephyr TV reads (Extensions.STANDARD). */
const STANDARD = 1;
const MAX_BYTES = 256 * 1024;

/** What an address holds, read and checked as Zephyr TV checks it. */
export type Read = {
  kind: "extension" | "repository";
  url: string;
  extId: string | null;
  name: string;
  description: string | null;
  author: string | null;
  icon: string | null;
  version: string | null;
  count: number | null;
  adult: boolean;
  homepage: string | null;
};

/** Why an address can't go in the catalogue: a key of the "catalogErrors" messages. */
export class Refused extends Error {
  constructor(readonly why: "address" | "unreachable" | "not_json" | "not_extension" | "newer" | "too_large") {
    super(why);
  }
}

/** The manifest's address from what was typed: an address ending in one of the two names, or the folder it's in. */
export function manifestUrl(typed: string): string | null {
  let u: URL;
  try {
    u = new URL(typed.trim());
  } catch {
    return null;
  }
  if (u.protocol !== "https:" || u.username || u.password || typed.length > 1000) return null;
  u.hash = "";
  if (!u.pathname.endsWith(PUBLISHED) && !u.pathname.endsWith(REPOSITORY)) u.pathname = u.pathname.replace(/\/?$/, `/${PUBLISHED}`);
  return u.toString();
}

/** Addresses that aren't on the internet: this machine, the local network (the database, Coolify), and the like. */
const PRIVATE = new BlockList();
for (const [net, bits] of [["0.0.0.0", 8], ["10.0.0.0", 8], ["100.64.0.0", 10], ["127.0.0.0", 8], ["169.254.0.0", 16],
  ["172.16.0.0", 12], ["192.0.0.0", 24], ["192.168.0.0", 16], ["198.18.0.0", 15], ["224.0.0.0", 3]] as const) PRIVATE.addSubnet(net, bits, "ipv4");
for (const [net, bits] of [["::", 127], ["fc00::", 7], ["fe80::", 10], ["ff00::", 8]] as const) PRIVATE.addSubnet(net, bits, "ipv6");

/** Whether [host] is only on the internet (every address it has). */
async function publicHost(host: string) {
  const h = host.replace(/^\[|\]$/g, "");
  if (/^(localhost|.*\.local|.*\.internal)$/i.test(h)) return false;
  const all = isIP(h) ? [{ address: h, family: isIP(h) }] : await lookup(h, { all: true }).catch(() => []);
  return all.length > 0 && all.every((a) => !blocked(a.address, a.family));
}

/** Whether [address] is a private one; an IPv4 written as IPv6 (::ffff:10.0.0.1) checked as the IPv4 it is. */
function blocked(address: string, family: number) {
  const mapped = address.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/i)?.[1];
  if (mapped) return PRIVATE.check(mapped, "ipv4");
  if (family === 6 && /^::ffff:/i.test(address)) return true; // ::ffff: in hex: not worth telling apart
  return PRIVATE.check(address, family === 6 ? "ipv6" : "ipv4");
}

/** [url]'s JSON: HTTPS, on the internet, at most 256 KB, ten seconds, up to three redirects (each checked again). */
async function fetchJson(url: string): Promise<unknown> {
  let at = url;
  for (let hops = 0; hops < 4; hops++) {
    const u = new URL(at);
    if (u.protocol !== "https:" || !(await publicHost(u.hostname))) throw new Refused("address");
    const res = await fetch(at, { redirect: "manual", signal: AbortSignal.timeout(10_000), headers: { accept: "application/json", "user-agent": "ZephyrTV-site" } })
      .catch(() => { throw new Refused("unreachable"); });
    if (res.status >= 300 && res.status < 400 && res.headers.get("location")) {
      at = new URL(res.headers.get("location")!, at).toString();
      continue;
    }
    if (!res.ok || !res.body) throw new Refused("unreachable");
    const reader = res.body.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_BYTES) {
        await reader.cancel();
        throw new Refused("too_large");
      }
      chunks.push(value);
    }
    try {
      return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      throw new Refused("not_json");
    }
  }
  throw new Refused("unreachable");
}

const ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const VERSION = /^\d+(\.\d+){0,2}(-[0-9A-Za-z.-]+)?$/;
const str = (v: unknown, n: number) => (typeof v === "string" && v.trim() ? v.trim().slice(0, n) : null);
const httpsFrom = (v: unknown, base: string) => {
  if (typeof v !== "string" || !v.trim()) return null;
  try {
    const u = new URL(v, base);
    return u.protocol === "https:" ? u.toString() : null;
  } catch {
    return null;
  }
};

/** What's at [url] (see manifestUrl), checked as Zephyr TV checks it. Throws Refused. */
export async function readManifest(url: string): Promise<Read> {
  const m = await fetchJson(url);
  if (!m || typeof m !== "object" || Array.isArray(m)) throw new Refused("not_extension");
  const o = m as Record<string, unknown>;
  const zephyr = typeof o.zephyr === "number" ? o.zephyr : 0;
  const name = str(o.name, 80);
  if (zephyr < 1 || !name) throw new Refused("not_extension");
  if (zephyr > STANDARD) throw new Refused("newer");
  const common = { url, name, description: str(o.description, 500), author: str(o.author, 80), icon: httpsFrom(o.icon, url), homepage: httpsFrom(o.homepage, url) };
  if (Array.isArray(o.extensions) && !("package" in o)) {
    const count = o.extensions.filter((e) => typeof e === "string" && e.trim()).length;
    if (!count) throw new Refused("not_extension");
    return { ...common, kind: "repository", extId: null, version: null, count, adult: o.adult === true };
  }
  const id = typeof o.id === "string" ? o.id : "";
  const version = typeof o.version === "string" ? o.version : "";
  if (!ID.test(id) || !VERSION.test(version) || typeof o.package !== "string" || !/^[0-9a-fA-F]{64}$/.test(String(o.sha256 ?? "")))
    throw new Refused("not_extension");
  return { ...common, kind: "extension", extId: id, version, count: null, adult: o.adult === true };
}

/** The ztv: link that installs [url] in Zephyr TV (Links.kt: ext?u=…), without its scheme. */
export const installLink = (url: string) => `ext?u=${encodeURIComponent(url)}`;

/** Zephyr TV's own extensions: the repository the app knows as official too (ExtensionSettings.OFFICIAL_REPOSITORY). */
export const OFFICIAL_REPOSITORY =
  process.env.OFFICIAL_REPOSITORY || "https://raw.githubusercontent.com/mccoy88f/Zephyr-TV-Extensions/main/repository-zephyrtv.json";

/**
 * The catalogue's official extensions as OFFICIAL_REPOSITORY lists them now: each read from its manifest (name,
 * version, description, icon as they are today) and published, marked official; one no longer listed taken out. One
 * that can't be read just now stays as it was. How many are in.
 */
export async function syncOfficial(): Promise<number> {
  const repo = await fetchJson(OFFICIAL_REPOSITORY);
  const list = (repo as { extensions?: unknown })?.extensions;
  if (!Array.isArray(list)) throw new Refused("not_extension");
  const urls = list.map((e) => httpsFrom(e, OFFICIAL_REPOSITORY)).filter((u): u is string => !!u);
  const now = new Date();
  let n = 0;
  for (const url of urls) {
    const read = await readManifest(url).catch((e) => {
      console.warn(`[catalog] official ${url}: ${e instanceof Error ? e.message : e}`);
      return null;
    });
    if (!read) continue;
    const row = { ...read, official: true, status: "approved" as const, checkedAt: now };
    await db.insert(catalog).values({ ...row, reviewedAt: now }).onConflictDoUpdate({ target: catalog.url, set: row });
    n++;
  }
  await db.update(catalog).set({ official: false, status: "removed", reviewedAt: now })
    .where(and(eq(catalog.official, true), urls.length ? notInArray(catalog.url, urls) : undefined));
  return n;
}


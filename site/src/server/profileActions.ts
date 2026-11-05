"use server";

import { and, asc, count, eq } from "drizzle-orm";
import { getLocale } from "next-intl/server";
import { db } from "@/db";
import { devices, profiles } from "@/db/schema";
import { redirect } from "@/i18n/navigation";
import { parseAvatar } from "@/lib/avatars";
import { answerLink, normalizeCode, pendingLink } from "./devices";
import { currentAccount } from "./session";
import { dropUserDocs } from "./sync";

const MAX_USERS = 12;
const field = (data: FormData, name: string) => String(data.get(name) ?? "");
const uuid = /^[0-9a-f-]{36}$/;

async function signedIn() {
  const account = await currentAccount();
  if (!account) redirect({ href: "/login", locale: await getLocale() });
  return account!;
}

async function toAccount(hash = "") {
  redirect({ href: `/account${hash}`, locale: await getLocale() });
}

/** A new Zephyr TV user of the account, at the end of its list (12 at most). */
export async function addProfile(data: FormData) {
  const account = await signedIn();
  const name = field(data, "name").trim().slice(0, 40);
  const [n] = await db.select({ n: count() }).from(profiles).where(eq(profiles.accountId, account.id));
  if (name && n.n < MAX_USERS) await db.insert(profiles).values({ accountId: account.id, name, position: n.n });
  await toAccount("#users");
}

/** A user's name or avatar changed ("" for none: their initial), from their page. */
export async function saveProfile(data: FormData) {
  const account = await signedIn();
  const id = field(data, "id");
  if (!uuid.test(id)) return toAccount("#users");
  const set: { name?: string; avatar?: string | null; updatedAt: Date } = { updatedAt: new Date() };
  if (data.has("name")) {
    const name = field(data, "name").trim().slice(0, 40);
    if (name) set.name = name;
  }
  if (data.has("avatar")) {
    const avatar = field(data, "avatar");
    set.avatar = avatar === "" ? null : parseAvatar(avatar) ? avatar : undefined;
    if (set.avatar === undefined) delete set.avatar;
  }
  await db.update(profiles).set(set).where(and(eq(profiles.id, id), eq(profiles.accountId, account.id)));
  redirect({ href: `/account/users/${id}?saved=1`, locale: await getLocale() });
}

/** A user moved one place up or down the account's list. */
export async function moveProfile(data: FormData) {
  const account = await signedIn();
  const id = field(data, "id");
  const list = await db.select().from(profiles).where(eq(profiles.accountId, account.id)).orderBy(asc(profiles.position), asc(profiles.createdAt));
  const at = list.findIndex((p) => p.id === id);
  const to = at + (field(data, "dir") === "up" ? -1 : 1);
  if (at >= 0 && to >= 0 && to < list.length) {
    [list[at], list[to]] = [list[to], list[at]];
    await Promise.all(list.map((p, i) => db.update(profiles).set({ position: i }).where(eq(profiles.id, p.id))));
  }
  await toAccount("#users");
}

/** A user gone from the account (on the TVs, at their next sync). */
export async function deleteProfile(data: FormData) {
  const account = await signedIn();
  const id = field(data, "id");
  if (uuid.test(id)) {
    await db.delete(profiles).where(and(eq(profiles.id, id), eq(profiles.accountId, account.id)));
    await dropUserDocs(account.id, id);
  }
  await toAccount("#users");
}

/** A linked TV unlinked: its token stops working. */
export async function unlinkDevice(data: FormData) {
  const account = await signedIn();
  const id = field(data, "id");
  if (uuid.test(id)) await db.delete(devices).where(and(eq(devices.id, id), eq(devices.accountId, account.id)));
  await toAccount("#tvs");
}

/** The TV showing [code] linked to this account (allow), or refused. Back to the account's TVs. */
export async function answerTv(data: FormData) {
  const account = await signedIn();
  const code = normalizeCode(field(data, "code"));
  if (code && (await pendingLink(code))) await answerLink(code, account.id, field(data, "allow") === "1");
  redirect({ href: field(data, "allow") === "1" ? "/account?done=tv#tvs" : "/account#tvs", locale: await getLocale() });
}

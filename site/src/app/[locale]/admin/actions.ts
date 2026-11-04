"use server";

import { eq } from "drizzle-orm";
import { getLocale } from "next-intl/server";
import { db } from "@/db";
import { accounts } from "@/db/schema";
import { redirect } from "@/i18n/navigation";
import { currentAccount, endAllSessions } from "@/server/session";

/** The account [data] names, if the one asking is the superadmin and it isn't their own; else back to sign in. */
async function target(data: FormData) {
  const me = await currentAccount();
  if (me?.role !== "superadmin") return redirect({ href: "/login", locale: await getLocale() });
  const id = String(data.get("id") ?? "");
  if (!/^[0-9a-f-]{36}$/.test(id) || id === me.id) return null;
  return id;
}

async function back() {
  redirect({ href: "/admin/accounts", locale: await getLocale() });
}

/** Blocked: signed out everywhere, and can't sign in again until unblocked. */
export async function block(data: FormData) {
  const id = await target(data);
  if (id) {
    await db.update(accounts).set({ blockedAt: new Date() }).where(eq(accounts.id, id));
    await endAllSessions(id);
  }
  await back();
}

export async function unblock(data: FormData) {
  const id = await target(data);
  if (id) await db.update(accounts).set({ blockedAt: null }).where(eq(accounts.id, id));
  await back();
}

/** Its email taken as confirmed, without the link (an email that never arrived). */
export async function markVerified(data: FormData) {
  const id = await target(data);
  if (id) await db.update(accounts).set({ emailVerifiedAt: new Date() }).where(eq(accounts.id, id));
  await back();
}

/** Gone, with everything that belongs to it. */
export async function remove(data: FormData) {
  const id = await target(data);
  if (id) await db.delete(accounts).where(eq(accounts.id, id));
  await back();
}

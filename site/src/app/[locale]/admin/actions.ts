"use server";

import { eq } from "drizzle-orm";
import { getLocale } from "next-intl/server";
import { db } from "@/db";
import { accounts } from "@/db/schema";
import { redirect } from "@/i18n/navigation";
import { currentAccount, endAllSessions } from "@/server/session";
import { sendMail } from "@/server/mail";

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

/**
 * A test email to the address given (the superadmin's own by default): sent, or the SMTP server's own answer (what
 * failed: no password set, port closed, wrong password…), shown on the panel. No password or address in it.
 */
export async function testMail(data: FormData) {
  const me = await currentAccount();
  const locale = await getLocale();
  if (me?.role !== "superadmin") return redirect({ href: "/login", locale });
  const to = String(data.get("to") ?? "").trim() || me.email;
  let result = "ok";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(to)) result = "invalid address";
  else {
    try {
      await sendMail(to, "Zephyr TV: test", `Test email from ${process.env.SITE_URL || "the site"}.`, "<p>Test email from Zephyr TV's site.</p>");
    } catch (e) {
      const err = e as { code?: string; responseCode?: number; response?: string; message?: string };
      result = [err.code, err.responseCode, err.response ?? err.message].filter(Boolean).join(" · ").slice(0, 300) || "error";
      console.warn("test mail failed", e);
    }
  }
  redirect({ href: `/admin?mail=${encodeURIComponent(result)}&to=${encodeURIComponent(to)}`, locale });
}


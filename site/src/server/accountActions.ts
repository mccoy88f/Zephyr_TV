"use server";

import { and, eq, ne } from "drizzle-orm";
import { headers } from "next/headers";
import { getLocale } from "next-intl/server";
import { z } from "zod";
import { db } from "@/db";
import { accounts, devices, linkCodes, profiles, syncDocs } from "@/db/schema";
import { redirect } from "@/i18n/navigation";
import { routing } from "@/i18n/routing";
import { sendLink, useLink } from "./emails";
import { allow } from "./limit";
import { hashPassword, verifyPassword } from "./password";
import { checkNewPassword } from "./passwords";
import { currentAccount, endAllSessions, endOtherSessions, endSession, startSession } from "./session";

/** A form's answer: done, or what went wrong (a key of the "forms" messages). */
export type FormState = { ok?: boolean; error?: string };

const email = z.string().trim().toLowerCase().email().max(254);
const ip = async () => (await headers()).get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
const field = (data: FormData, name: string) => String(data.get(name) ?? "");

/** A new password and its repetition checked: equal, long enough, not leaked. A "forms" key when not. */
async function newPasswordProblem(password: string, again: string) {
  if (password !== again) return "pw_mismatch";
  const problem = await checkNewPassword(password);
  return problem ? `pw_${problem}` : null;
}

/**
 * Sign up: an account in this page's language, its email to confirm (a link valid a day). An email that has an account
 * already gets the same answer (it never says which emails have one); an unconfirmed one gets its link again, with this
 * password. At most 5 sign-ups an hour from one address.
 */
export async function register(_: FormState, data: FormData): Promise<FormState> {
  if (!allow(`register:${await ip()}`, 5, 3_600_000)) return { error: "limited" };
  const parsed = email.safeParse(data.get("email"));
  if (!parsed.success) return { error: "email_invalid" };
  const password = field(data, "password");
  const problem = await newPasswordProblem(password, field(data, "password2"));
  if (problem) return { error: problem };
  const locale = await getLocale();
  const [found] = await db.select().from(accounts).where(eq(accounts.email, parsed.data));
  if (found?.emailVerifiedAt) return { ok: true };
  const passwordHash = await hashPassword(password);
  const [account] = found
    ? await db.update(accounts).set({ passwordHash, locale }).where(eq(accounts.id, found.id)).returning()
    : await db.insert(accounts).values({ email: parsed.data, passwordHash, locale }).returning();
  try {
    await sendLink(account.id, "verify", account.email, locale);
  } catch (e) {
    console.warn("verify email failed", e);
    return { error: "mail" };
  }
  return { ok: true };
}

/** The confirmation link sent again to an unconfirmed account's email: at most once in 2 minutes. Same answer for any email. */
export async function resend(_: FormState, data: FormData): Promise<FormState> {
  const parsed = email.safeParse(data.get("email"));
  if (!parsed.success) return { error: "email_invalid" };
  if (!allow(`resend:${parsed.data}`, 1, 120_000) || !allow(`resend:${await ip()}`, 10, 3_600_000)) return { error: "limited" };
  const [found] = await db.select().from(accounts).where(eq(accounts.email, parsed.data));
  if (found && !found.emailVerifiedAt && !found.blockedAt) {
    await sendLink(found.id, "verify", found.email, found.locale).catch((e) => console.warn("resend failed", e));
  }
  return { ok: true };
}

/**
 * A link from an email used (the button on /verify, not the link itself, so a mail scanner opening it uses nothing):
 * an email confirmed (and signed in), or an account moved to its new email.
 */
export async function confirmLink(_: FormState, data: FormData): Promise<FormState> {
  const row = await useLink(field(data, "token"), ["verify", "change"]);
  if (!row) return { error: "link_bad" };
  if (row.purpose === "change" && row.newEmail) {
    const [taken] = await db.select({ id: accounts.id }).from(accounts).where(and(eq(accounts.email, row.newEmail), ne(accounts.id, row.accountId)));
    if (taken) return { error: "email_taken" };
    await db.update(accounts).set({ email: row.newEmail, emailVerifiedAt: new Date() }).where(eq(accounts.id, row.accountId));
  } else {
    await db.update(accounts).set({ emailVerifiedAt: new Date() }).where(eq(accounts.id, row.accountId));
  }
  const [account] = await db.select().from(accounts).where(eq(accounts.id, row.accountId));
  if (!account || account.blockedAt) return { error: "link_bad" };
  await startSession(account.id);
  redirect({ href: "/account?done=email", locale: await getLocale() });
  return { ok: true };
}

/** A link to set a new password (valid an hour), to a confirmed account's email. Same answer for any email. */
export async function forgot(_: FormState, data: FormData): Promise<FormState> {
  const parsed = email.safeParse(data.get("email"));
  if (!parsed.success) return { error: "email_invalid" };
  if (!allow(`forgot:${parsed.data}`, 3, 3_600_000) || !allow(`forgot:${await ip()}`, 10, 3_600_000)) return { error: "limited" };
  const [found] = await db.select().from(accounts).where(eq(accounts.email, parsed.data));
  if (found?.emailVerifiedAt && !found.blockedAt) {
    await sendLink(found.id, "reset", found.email, found.locale).catch((e) => console.warn("reset email failed", e));
  }
  return { ok: true };
}

/** A new password from the reset link: every session ends, this browser signs in. */
export async function resetPassword(_: FormState, data: FormData): Promise<FormState> {
  const password = field(data, "password");
  const problem = await newPasswordProblem(password, field(data, "password2"));
  if (problem) return { error: problem };
  const row = await useLink(field(data, "token"), ["reset"]);
  if (!row) return { error: "link_bad" };
  await db.update(accounts).set({ passwordHash: await hashPassword(password) }).where(eq(accounts.id, row.accountId));
  await endAllSessions(row.accountId);
  await startSession(row.accountId);
  redirect({ href: "/account?done=password", locale: await getLocale() });
  return { ok: true };
}

/** The account signed in here, or back to sign in. */
async function signedIn() {
  const account = await currentAccount();
  if (!account) redirect({ href: "/login", locale: await getLocale() });
  return account!;
}

/** A new password, given the current one: the other sessions end. */
export async function changePassword(_: FormState, data: FormData): Promise<FormState> {
  const account = await signedIn();
  if (!allow(`change:${account.id}`, 10, 3_600_000)) return { error: "limited" };
  if (!(await verifyPassword(account.passwordHash, field(data, "current")))) return { error: "pw_wrong" };
  const password = field(data, "password");
  const problem = await newPasswordProblem(password, field(data, "password2"));
  if (problem) return { error: problem };
  await db.update(accounts).set({ passwordHash: await hashPassword(password) }).where(eq(accounts.id, account.id));
  await endOtherSessions(account.id);
  return { ok: true };
}

/** A new email, given the password: a link to it, which moves the account there once used. */
export async function changeEmail(_: FormState, data: FormData): Promise<FormState> {
  const account = await signedIn();
  if (!allow(`email:${account.id}`, 5, 3_600_000)) return { error: "limited" };
  const parsed = email.safeParse(data.get("email"));
  if (!parsed.success) return { error: "email_invalid" };
  if (!(await verifyPassword(account.passwordHash, field(data, "current")))) return { error: "pw_wrong" };
  if (parsed.data === account.email) return { ok: true };
  const [taken] = await db.select({ id: accounts.id }).from(accounts).where(eq(accounts.email, parsed.data));
  if (taken) return { error: "email_taken" };
  try {
    await sendLink(account.id, "change", parsed.data, account.locale, parsed.data);
  } catch (e) {
    console.warn("change email failed", e);
    return { error: "mail" };
  }
  return { ok: true };
}

/** The language the site's emails are written in. */
export async function setLanguage(data: FormData) {
  const account = await signedIn();
  const locale = field(data, "locale");
  if ((routing.locales as readonly string[]).includes(locale)) await db.update(accounts).set({ locale }).where(eq(accounts.id, account.id));
  redirect({ href: "/account", locale: await getLocale() });
}

/** Every other browser signed out. */
export async function signOutOthers() {
  const account = await signedIn();
  await endOtherSessions(account.id);
  redirect({ href: "/account?done=sessions", locale: await getLocale() });
}

/** The account gone, with everything that belongs to it, given its password. The superadmin's can't be. */
export async function deleteAccount(_: FormState, data: FormData): Promise<FormState> {
  const account = await signedIn();
  if (account.role === "superadmin") return { error: "superadmin" };
  if (!(await verifyPassword(account.passwordHash, field(data, "current")))) return { error: "pw_wrong" };
  await db.delete(accounts).where(eq(accounts.id, account.id));
  await endSession();
  redirect({ href: "/?done=deleted", locale: await getLocale() });
  return { ok: true };
}

/**
 * The account back as it was when made (its password asked first): its TVs unlinked first (their tokens stop
 * working; each TV keeps what it has, as a TV of its own), then its Zephyr TV users and everything synced gone.
 * The account itself, its email, password, sign-ins and extension proposals stay.
 */
export async function resetAccount(_: FormState, data: FormData): Promise<FormState> {
  const account = await signedIn();
  if (!(await verifyPassword(account.passwordHash, field(data, "current")))) return { error: "pw_wrong" };
  await db.delete(devices).where(eq(devices.accountId, account.id));
  await db.delete(linkCodes).where(eq(linkCodes.accountId, account.id));
  await db.delete(syncDocs).where(eq(syncDocs.accountId, account.id));
  await db.delete(profiles).where(eq(profiles.accountId, account.id));
  redirect({ href: "/account?done=reset", locale: await getLocale() });
  return { ok: true };
}


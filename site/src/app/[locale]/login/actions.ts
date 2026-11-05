"use server";

import { eq } from "drizzle-orm";
import { headers } from "next/headers";
import { getLocale } from "next-intl/server";
import { z } from "zod";
import { db } from "@/db";
import { accounts } from "@/db/schema";
import { redirect } from "@/i18n/navigation";
import { allow } from "@/server/limit";
import { hashPassword, verifyPassword } from "@/server/password";
import { startSession } from "@/server/session";

export type LoginState = { error?: "wrong" | "limited" | "unverified" | "invalid" };

const Form = z.object({ email: z.string().trim().toLowerCase().email().max(254), password: z.string().min(1).max(1024) });

// Compared against when the email isn't known, so a wrong email takes as long as a wrong password.
let decoy: Promise<string> | null = null;

/**
 * Sign in with email and password. One error for a wrong email or password alike (it never says whether an email has
 * an account); at most 10 tries in 15 minutes from one address, and on one email.
 */
export async function login(_: LoginState, data: FormData): Promise<LoginState> {
  const parsed = Form.safeParse({ email: data.get("email"), password: data.get("password") });
  if (!parsed.success) return { error: "invalid" };
  const { email, password } = parsed.data;
  const ip = (await headers()).get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`ip:${ip}`, 10, 15 * 60_000) || !allow(`email:${email}`, 10, 15 * 60_000)) return { error: "limited" };
  const [account] = await db.select().from(accounts).where(eq(accounts.email, email));
  if (!account) {
    decoy ??= hashPassword("decoy-password");
    await verifyPassword(await decoy, password);
    return { error: "wrong" };
  }
  if (!(await verifyPassword(account.passwordHash, password)) || account.blockedAt) return { error: "wrong" };
  if (!account.emailVerifiedAt) return { error: "unverified" };
  await startSession(account.id);
  // Back where sign-in was asked for (a TV's code to allow, the account's page), only ever a page of this site.
  const next = String(data.get("next") ?? "");
  const safe = /^\/[a-z0-9/?=&%._-]*$/i.test(next) && !next.startsWith("//") ? next : null;
  redirect({ href: safe ?? (account.role === "superadmin" ? "/admin" : "/account"), locale: await getLocale() });
  return {};
}

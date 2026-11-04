import "server-only";
import { createHash, randomBytes } from "node:crypto";
import { and, eq, gt } from "drizzle-orm";
import { getTranslations } from "next-intl/server";
import { db } from "@/db";
import { emailTokens } from "@/db/schema";
import { routing } from "@/i18n/routing";
import { sendMail } from "./mail";

type Purpose = "verify" | "reset" | "change";

/** How long each link works: a day to confirm an email, an hour to set a new password. */
const HOURS: Record<Purpose, number> = { verify: 24, reset: 1, change: 24 };

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
const escape = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

/**
 * A link for [accountId] that does [purpose], sent to [to] in [locale]: its token in the link, its hash in the
 * database; links of the same purpose sent before stop working. Throws when the email can't be sent.
 */
export async function sendLink(accountId: string, purpose: Purpose, to: string, locale: string, newEmail?: string) {
  const token = randomBytes(32).toString("base64url");
  await db.delete(emailTokens).where(and(eq(emailTokens.accountId, accountId), eq(emailTokens.purpose, purpose)));
  await db.insert(emailTokens).values({
    tokenHash: sha256(token), accountId, purpose, newEmail: newEmail ?? null,
    expiresAt: new Date(Date.now() + HOURS[purpose] * 3_600_000),
  });
  const lang = (routing.locales as readonly string[]).includes(locale) ? locale : routing.defaultLocale;
  const site = process.env.SITE_URL || "https://ztv.mccoy88f.link";
  const link = `${site}/${lang}/${purpose === "reset" ? "reset" : "verify"}?token=${token}`;
  const t = await getTranslations({ locale: lang, namespace: "email" });
  const subject = t(`${purpose}_subject`);
  const lines = [t("hello"), t(`${purpose}_text`, { hours: HOURS[purpose] }), link, t("ignore")];
  const html = `<div style="font:16px/1.5 system-ui,sans-serif;max-width:520px">
    <p>${escape(lines[0])}</p><p>${escape(lines[1])}</p>
    <p><a href="${escape(link)}" style="display:inline-block;padding:12px 20px;border-radius:10px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600">${escape(t(`${purpose}_button`))}</a></p>
    <p style="color:#666;font-size:14px">${escape(lines[3])}</p><p style="color:#666;font-size:14px">Zephyr TV · ${escape(site)}</p></div>`;
  await sendMail(to, subject, `${lines.join("\n\n")}\n\nZephyr TV · ${site}`, html);
}

/** The link's token used ([purposes] its kind): its row, gone from the database; null when unknown, used or too old. */
export async function useLink(token: string, purposes: Purpose[]) {
  if (!/^[A-Za-z0-9_-]{20,100}$/.test(token)) return null;
  const [row] = await db.delete(emailTokens)
    .where(and(eq(emailTokens.tokenHash, sha256(token)), gt(emailTokens.expiresAt, new Date())))
    .returning();
  return row && purposes.includes(row.purpose) ? row : null;
}

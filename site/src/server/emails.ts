import "server-only";
import { createHash, randomBytes } from "node:crypto";
import { existsSync } from "node:fs";
import path from "node:path";
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
  const body = t(`${purpose}_text`, { hours: HOURS[purpose] });
  const text = [t("hello"), body, link, t("ignore"), `Zephyr TV · ${site}`].join("\n\n");
  const logo = path.join(process.cwd(), "public", "logo.png");
  const hasLogo = existsSync(logo);
  const html = page({
    lang, site, title: subject, hello: t("hello"), body, button: t(`${purpose}_button`), link,
    hint: t("link_hint"), ignore: t("ignore"), tagline: t("tagline"), logo: hasLogo ? "cid:logo@ztv" : `${site}/logo.png`,
  });
  await sendMail(to, subject, text, html, hasLogo ? [{ filename: "logo.png", path: logo, cid: "logo@ztv" }] : []);
}

/**
 * An email as the site sends it: Zephyr TV's logo and name on top, a white card with its title, its text and one
 * button, the link written out under it (for a button that doesn't work), and a line about the site. Tables and
 * inline styles only, as email apps want; light, so it reads the same in all of them.
 */
function page(p: { lang: string; site: string; title: string; hello: string; body: string; button: string; link: string;
  hint: string; ignore: string; tagline: string; logo: string }) {
  const e = escape;
  const font = "font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";
  return `<!doctype html><html lang="${e(p.lang)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>${e(p.title)}</title></head>
<body style="margin:0;padding:0;background:#eef2f7">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#eef2f7"><tr><td align="center" style="padding:32px 16px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px">
<tr><td align="center" style="padding:0 0 20px">
  <img src="${e(p.logo)}" width="64" height="64" alt="Zephyr TV" style="display:block;border:0;border-radius:16px">
  <div style="${font};font-size:20px;font-weight:700;color:#0f172a;padding-top:10px">Zephyr TV</div>
</td></tr>
<tr><td style="background:#ffffff;border-radius:16px;padding:32px 32px 28px;box-shadow:0 8px 24px rgba(15,23,42,0.08)">
  <h1 style="${font};font-size:22px;line-height:1.3;color:#0f172a;margin:0 0 16px">${e(p.title)}</h1>
  <p style="${font};font-size:16px;line-height:1.6;color:#334155;margin:0 0 8px">${e(p.hello)}</p>
  <p style="${font};font-size:16px;line-height:1.6;color:#334155;margin:0 0 24px">${e(p.body)}</p>
  <table role="presentation" cellpadding="0" cellspacing="0"><tr><td style="border-radius:12px;background:#2563eb;background-image:linear-gradient(135deg,#38bdf8,#2563eb)">
    <a href="${e(p.link)}" style="${font};display:inline-block;padding:14px 26px;font-size:16px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:12px">${e(p.button)}</a>
  </td></tr></table>
  <p style="${font};font-size:13px;line-height:1.5;color:#64748b;margin:24px 0 4px">${e(p.hint)}</p>
  <p style="${font};font-size:13px;line-height:1.5;margin:0;word-break:break-all"><a href="${e(p.link)}" style="color:#2563eb">${e(p.link)}</a></p>
  <hr style="border:0;border-top:1px solid #e2e8f0;margin:24px 0 16px">
  <p style="${font};font-size:13px;line-height:1.5;color:#64748b;margin:0">${e(p.ignore)}</p>
</td></tr>
<tr><td align="center" style="padding:20px 8px 0;${font};font-size:12px;line-height:1.6;color:#94a3b8">
  ${e(p.tagline)}<br><a href="${e(p.site)}" style="color:#64748b;text-decoration:none">${e(p.site.replace(/^https?:\/\//, ""))}</a>
</td></tr>
</table></td></tr></table></body></html>`;
}

/** The link's token used ([purposes] its kind): its row, gone from the database; null when unknown, used or too old. */
export async function useLink(token: string, purposes: Purpose[]) {
  if (!/^[A-Za-z0-9_-]{20,100}$/.test(token)) return null;
  const [row] = await db.delete(emailTokens)
    .where(and(eq(emailTokens.tokenHash, sha256(token)), gt(emailTokens.expiresAt, new Date())))
    .returning();
  return row && purposes.includes(row.purpose) ? row : null;
}

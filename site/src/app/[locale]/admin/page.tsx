import { count, eq, gt, isNotNull } from "drizzle-orm";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { db } from "@/db";
import { accounts, sessions } from "@/db/schema";
import { Link, redirect } from "@/i18n/navigation";
import { mailReady } from "@/server/mail";
import { currentAccount } from "@/server/session";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "admin" }))("title"), robots: { index: false } };
}

/** The superadmin's panel: for now what the site holds; users, TVs, sync and extensions come in the next phases. */
export default async function Admin({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const account = await currentAccount();
  if (account?.role !== "superadmin") return redirect({ href: "/login", locale });
  const t = await getTranslations("admin");
  const [[all], [verified], [live]] = await Promise.all([
    db.select({ n: count() }).from(accounts),
    db.select({ n: count() }).from(accounts).where(isNotNull(accounts.emailVerifiedAt)),
    db.select({ n: count() }).from(sessions).where(gt(sessions.expiresAt, new Date())),
  ]);
  const [admins] = await db.select({ n: count() }).from(accounts).where(eq(accounts.role, "superadmin"));
  return (
    <section>
      <h1>{t("title")}</h1>
      <p className="muted">{t("signed_in", { email: account.email })}</p>
      <div className="grid">
        <article className="card stat"><strong>{all.n}</strong><span>{t("accounts")}</span></article>
        <article className="card stat"><strong>{verified.n}</strong><span>{t("verified")}</span></article>
        <article className="card stat"><strong>{admins.n}</strong><span>{t("superadmins")}</span></article>
        <article className="card stat"><strong>{live.n}</strong><span>{t("sessions")}</span></article>
      </div>
      <p><Link className="button" href="/admin/accounts">{t("manage_accounts")}</Link></p>
      {!mailReady() && <p className="error">{t("no_mail")}</p>}
      <p className="muted">{t("soon")}</p>
    </section>
  );
}

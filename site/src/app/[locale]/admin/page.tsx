import { count, eq, gt, isNotNull } from "drizzle-orm";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { db } from "@/db";
import { accounts, catalog, sessions } from "@/db/schema";
import { Link, redirect } from "@/i18n/navigation";
import { mailReady } from "@/server/mail";
import { testMail } from "./actions";
import { currentAccount } from "@/server/session";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "admin" }))("title"), robots: { index: false } };
}

/** The superadmin's panel: what the site holds, the accounts, and the extensions waiting for review. */
export default async function Admin({ params, searchParams }: { params: Promise<{ locale: string }>; searchParams: Promise<{ mail?: string; to?: string }> }) {
  const { locale } = await params;
  const { mail, to } = await searchParams;
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
  const [pending] = await db.select({ n: count() }).from(catalog).where(eq(catalog.status, "pending"));
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
      <div className="actions left">
        <Link className="button" href="/admin/accounts">{t("manage_accounts")}</Link>
        <Link className={`button${pending.n ? " primary" : ""}`} href="/admin/extensions">{t("manage_extensions", { n: pending.n })}</Link>
      </div>
      {!mailReady() && <p className="error">{t("no_mail")}</p>}
      <article className="card">
        <h2>{t("mail_title")}</h2>
        <p>{t("mail_text", { host: process.env.SMTP_HOST || "ssl0.ovh.net", port: process.env.SMTP_PORT || "465", user: process.env.SMTP_USER || "—" })}</p>
        <form className="search" action={testMail}>
          <input name="to" type="email" placeholder={account.email} aria-label={t("mail_to")} defaultValue={to ?? ""} />
          <button className="button" type="submit">{t("mail_send")}</button>
        </form>
        {mail && (mail === "ok"
          ? <p className="okmsg" role="status">{t("mail_ok", { to: to ?? "" })}</p>
          : <p className="error" role="alert">{t("mail_failed")} <code>{mail}</code></p>)}
      </article>
      <p className="muted">{t("soon")}</p>
    </section>
  );
}

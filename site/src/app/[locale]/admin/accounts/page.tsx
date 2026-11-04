import { desc, ilike } from "drizzle-orm";
import { getFormatter, getTranslations, setRequestLocale } from "next-intl/server";
import { db } from "@/db";
import { accounts } from "@/db/schema";
import { Link, redirect } from "@/i18n/navigation";
import { currentAccount } from "@/server/session";
import { block, markVerified, remove, unblock } from "../actions";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "adminAccounts" }))("title"), robots: { index: false } };
}

/** The superadmin's list of accounts, newest first (200 at most; search by email): each one's state and actions. */
export default async function AdminAccounts({ params, searchParams }: {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ q?: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);
  const me = await currentAccount();
  if (me?.role !== "superadmin") return redirect({ href: "/login", locale });
  const t = await getTranslations("adminAccounts");
  const format = await getFormatter();
  const q = (await searchParams).q?.trim().slice(0, 100) ?? "";
  const list = await db.select().from(accounts)
    .where(q ? ilike(accounts.email, `%${q.replace(/[%_\\]/g, (c) => `\\${c}`)}%`) : undefined)
    .orderBy(desc(accounts.createdAt)).limit(200);

  return (
    <section>
      <p><Link href="/admin">← {t("back")}</Link></p>
      <h1>{t("title")}</h1>
      <form className="search" method="get">
        <input name="q" type="search" defaultValue={q} placeholder={t("search")} aria-label={t("search")} />
        <button className="button" type="submit">{t("search")}</button>
      </form>
      <div className="table">
        <table>
          <thead>
            <tr><th>{t("email")}</th><th>{t("state")}</th><th>{t("created")}</th><th></th></tr>
          </thead>
          <tbody>
            {list.map((a) => (
              <tr key={a.id}>
                <td>{a.email}{a.role === "superadmin" && <span className="tag">superadmin</span>}</td>
                <td>
                  {a.blockedAt ? <span className="tag bad">{t("blocked")}</span> : a.emailVerifiedAt ? t("verified") : <span className="tag warn">{t("unverified")}</span>}
                </td>
                <td>{format.dateTime(a.createdAt, { dateStyle: "medium" })}</td>
                <td className="row-actions">
                  {a.id !== me.id && (
                    <>
                      {!a.emailVerifiedAt && <form action={markVerified}><input type="hidden" name="id" value={a.id} /><button className="linkish">{t("verify")}</button></form>}
                      {a.blockedAt
                        ? <form action={unblock}><input type="hidden" name="id" value={a.id} /><button className="linkish">{t("unblock")}</button></form>
                        : <form action={block}><input type="hidden" name="id" value={a.id} /><button className="linkish">{t("block")}</button></form>}
                      <form action={remove}><input type="hidden" name="id" value={a.id} /><button className="linkish danger-text">{t("delete")}</button></form>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {list.length === 0 && <p className="muted">{t("none")}</p>}
    </section>
  );
}

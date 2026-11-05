import { asc, desc, eq } from "drizzle-orm";
import { getFormatter, getTranslations, setRequestLocale } from "next-intl/server";
import { db } from "@/db";
import { accounts, catalog } from "@/db/schema";
import { Link, redirect } from "@/i18n/navigation";
import { deleteExtension, recheckExtension, reviewExtension } from "@/server/catalogActions";
import { currentAccount } from "@/server/session";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "catalogAdmin" }))("title"), robots: { index: false } };
}

const STATUSES = ["pending", "approved", "rejected", "removed"] as const;

/**
 * The superadmin's review of the catalogue: the proposals waiting first (oldest first), then the rest by status.
 * Each: what its manifest says, who proposed it and why, approve / refuse (with a note) / remove / read again.
 */
export default async function ReviewExtensions({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const me = await currentAccount();
  if (me?.role !== "superadmin") return redirect({ href: "/login", locale });
  const t = await getTranslations("catalogAdmin");
  const c = await getTranslations("catalog");
  const format = await getFormatter();
  const rows = await db.select({ e: catalog, by: accounts.email }).from(catalog).leftJoin(accounts, eq(catalog.submittedBy, accounts.id))
    .orderBy(asc(catalog.status), desc(catalog.createdAt));
  const ordered = STATUSES.flatMap((s) => {
    const of = rows.filter((r) => r.e.status === s);
    return s === "pending" ? of.reverse() : of;
  });
  const move = (id: string, status: string, label: string, danger = false, note = false) => (
    <form action={reviewExtension} className="review">
      <input type="hidden" name="id" value={id} />
      <input type="hidden" name="status" value={status} />
      {note && <input name="review" placeholder={t("note")} aria-label={t("note")} maxLength={1000} />}
      <button className={`button${danger ? " danger" : status === "approved" ? " primary" : ""}`} type="submit">{label}</button>
    </form>
  );

  return (
    <section>
      <p><Link href="/admin">← {t("back")}</Link></p>
      <h1>{t("title")}</h1>
      {ordered.length === 0 && <p className="muted">{t("none")}</p>}
      {STATUSES.map((s) => {
        const of = ordered.filter((r) => r.e.status === s);
        if (!of.length) return null;
        return (
          <div key={s}>
            <h2>{c(`status_${s}`)} ({of.length})</h2>
            <div className="grid two">
              {of.map(({ e, by }) => (
                <article key={e.id} className="card ext">
                  <div className="ext-head">
                    {e.icon ? <img src={e.icon} alt="" width={48} height={48} referrerPolicy="no-referrer" /> : <img src="/logo.png" alt="" width={48} height={48} />}
                    <div>
                      <h3>{e.name}</h3>
                      <p className="small">
                        <span className="tag">{e.kind === "repository" ? c("repository", { n: e.count ?? 0 }) : `${e.extId} v${e.version}`}</span>
                        {e.adult && <span className="tag bad">18+</span>}
                      </p>
                    </div>
                  </div>
                  {e.author && <p className="small">{c("by", { author: e.author })}</p>}
                  {e.description && <p>{e.description}</p>}
                  <p className="small"><a href={e.url} target="_blank" rel="noopener nofollow"><code className="url">{e.url}</code></a></p>
                  {e.homepage && <p className="small"><a href={e.homepage} target="_blank" rel="noopener nofollow">{e.homepage}</a></p>}
                  <p className="small">{t("by", { email: by ?? "—", date: format.dateTime(e.createdAt, { dateStyle: "medium", timeStyle: "short" }) })}</p>
                  {e.note && <p className="small">{t("their_note", { note: e.note })}</p>}
                  {e.review && <p className="small">{t("review", { note: e.review })}</p>}
                  <p className="small">{t("checked", { date: format.dateTime(e.checkedAt, { dateStyle: "medium", timeStyle: "short" }) })}</p>
                  <div className="row-actions">
                    {e.status !== "approved" && move(e.id, "approved", t("approve"))}
                    {e.status === "pending" && move(e.id, "rejected", t("reject"), true, true)}
                    {e.status === "approved" && move(e.id, "removed", t("remove"), true, true)}
                    <form action={recheckExtension}><input type="hidden" name="id" value={e.id} /><button className="button" type="submit">{t("recheck")}</button></form>
                    {e.status !== "pending" && e.status !== "approved" && (
                      <form action={deleteExtension}><input type="hidden" name="id" value={e.id} /><button className="linkish danger-text" type="submit">{t("delete")}</button></form>
                    )}
                  </div>
                </article>
              ))}
            </div>
          </div>
        );
      })}
    </section>
  );
}

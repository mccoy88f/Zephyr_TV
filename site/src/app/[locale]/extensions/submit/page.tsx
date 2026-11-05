import { desc, eq } from "drizzle-orm";
import { getFormatter, getTranslations, setRequestLocale } from "next-intl/server";
import { db } from "@/db";
import { catalog } from "@/db/schema";
import { Form } from "@/components/Form";
import { Link, redirect } from "@/i18n/navigation";
import { submitExtension, withdrawExtension } from "@/server/catalogActions";
import { currentAccount } from "@/server/session";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "catalog" }))("submit"), robots: { index: false } };
}

/** Proposing an extension or a repository (signed in): its address, a note for the review; and one's proposals so far. */
export default async function Submit({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const account = await currentAccount();
  if (!account) return redirect({ href: "/login?next=/extensions/submit", locale });
  const t = await getTranslations("catalog");
  const format = await getFormatter();
  const mine = await db.select().from(catalog).where(eq(catalog.submittedBy, account.id)).orderBy(desc(catalog.createdAt));
  return (
    <section>
      <p><Link href="/extensions">← {t("title")}</Link></p>
      <h1>{t("submit")}</h1>
      <p className="lead">{t("submit_intro")}</p>
      <article className="card">
        <Form action={submitExtension} submit={t("send")} done={t("sent")} fields={[
          { name: "url", label: t("url"), type: "url" },
          { name: "note", label: t("note"), required: false },
        ]} />
        <p className="muted small">{t("rules")}</p>
      </article>
      <h2>{t("mine")}</h2>
      {mine.length === 0 ? <p className="muted">{t("mine_none")}</p> : (
        <ul className="sessions">
          {mine.map((e) => (
            <li key={e.id}>
              <strong>{e.name}</strong>
              <span className={`tag${e.status === "rejected" || e.status === "removed" ? " bad" : e.status === "pending" ? " warn" : ""}`}>{t(`status_${e.status}`)}</span>
              <span className="muted small">{format.dateTime(e.createdAt, { dateStyle: "medium" })}</span>
              {e.review && e.status !== "approved" && <span className="small">{t("review_note", { note: e.review })}</span>}
              {e.status === "pending" && (
                <form action={withdrawExtension}><input type="hidden" name="id" value={e.id} /><button className="linkish danger-text">{t("withdraw")}</button></form>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

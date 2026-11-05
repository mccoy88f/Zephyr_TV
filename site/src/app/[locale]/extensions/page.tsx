import { asc, desc, eq } from "drizzle-orm";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { db } from "@/db";
import { catalog } from "@/db/schema";
import { Link } from "@/i18n/navigation";

export const dynamic = "force-dynamic";
const DOCS = "https://github.com/mccoy88f/Zephyr_TV/blob/main/docs/extensions.md";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "catalog" });
  return { title: t("title"), description: t("lead") };
}

/**
 * The extensions' catalogue: what Zephyr TV's extensions are, the ones reviewed and approved (each opened in Zephyr
 * TV to install it, see /o/ext), and proposing one's own.
 */
export default async function Extensions({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("catalog");
  const list = await db.select().from(catalog).where(eq(catalog.status, "approved")).orderBy(desc(catalog.official), asc(catalog.name)).catch(() => []);
  return (
    <section>
      <h1>{t("title")}</h1>
      <p className="lead">{t("lead")}</p>
      <div className="actions left">
        <Link className="button primary" href="/extensions/submit">{t("submit")}</Link>
        <a className="button" href={DOCS} target="_blank" rel="noopener">{t("docs")}</a>
      </div>
      <h2>{t("list_title")}</h2>
      {list.length === 0 ? <p className="muted">{t("empty")}</p> : (
        <div className="grid">
          {list.map((e) => (
            <article key={e.id} className="card ext">
              <div className="ext-head">
                {e.icon ? <img src={e.icon} alt="" width={48} height={48} loading="lazy" referrerPolicy="no-referrer" /> : <img src="/logo.png" alt="" width={48} height={48} />}
                <div>
                  <h3>{e.name}</h3>
                  <p className="small">
                    {e.kind === "repository" ? <span className="tag">{t("repository", { n: e.count ?? 0 })}</span> : e.version && <span className="tag">v{e.version}</span>}
                    {e.official && <span className="tag official">{t("official")}</span>}
                    {e.adult && <span className="tag bad">18+</span>}
                  </p>
                </div>
              </div>
              {e.author && <p className="small">{t("by", { author: e.author })}</p>}
              {e.description && <p>{e.description}</p>}
              <p className="ext-actions">
                <a className="button primary" href={`/o/ext?u=${encodeURIComponent(e.url)}`}>{t("install")}</a>
                {e.homepage && <a className="button" href={e.homepage} target="_blank" rel="noopener nofollow">{t("homepage")}</a>}
              </p>
              <p className="small"><code className="url">{e.url}</code></p>
            </article>
          ))}
        </div>
      )}
      <p className="muted small">{t("disclaimer")}</p>
    </section>
  );
}

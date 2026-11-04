import { getFormatter, getTranslations, setRequestLocale } from "next-intl/server";
import { type Release, latestReleases } from "@/server/releases";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "download" }))("title") };
}

export default async function Download({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("download");
  const format = await getFormatter();
  const releases = await latestReleases();

  function block(r: Release | null, label: string) {
    if (!r) return null;
    return (
      <article className="card release">
        <h3>{label} · {r.version}</h3>
        <p className="muted">{t("published", { date: format.dateTime(new Date(r.date), { dateStyle: "long" }) })}</p>
        {r.apk && <a className="button primary" href={r.apk}>{t("get_apk")}</a>}{" "}
        <a className="button" href={r.url}>{t("notes")}</a>
        {r.notes.length > 0 && (
          <ul className="notes">
            {r.notes.map((n, i) => <li key={i}>{n}</li>)}
          </ul>
        )}
      </article>
    );
  }

  return (
    <section>
      <h1>{t("title")}</h1>
      <p className="lead">{t("intro")}</p>
      {releases ? (
        <div className="grid two">
          {block(releases.stable, t("stable"))}
          {block(releases.beta, t("beta"))}
        </div>
      ) : (
        <p>
          {t("none")} <a href="https://github.com/mccoy88f/Zephyr_TV/releases">GitHub</a>
        </p>
      )}
      <h2>{t("install_title")}</h2>
      <p>{t("install_text")}</p>
    </section>
  );
}

import { getTranslations, setRequestLocale } from "next-intl/server";
import credits from "../../../../content/credits.json";
import siteLibs from "../../../../content/site-licenses.json";

type Item = { id: string; name: string; by: string; url: string; license: string };

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "credits" }))("title") };
}

export default async function Credits({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("credits");

  function list(items: Item[]) {
    return (
      <ul className="credits">
        {items.map((c) => (
          <li key={c.id}>
            <a href={c.url}>{c.name}</a> <span className="muted">· {c.by}</span>
            {c.license && <span className="tag">{c.license}</span>}
            <p>{t(`items.${c.id}`)}</p>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <section>
      <h1>{t("title")}</h1>
      <p className="lead">{t("intro")}</p>

      <h2>{t("origin")}</h2>
      {list(credits.origin)}

      <h2>{t("app")}</h2>
      {list(credits.app)}

      <h2>{t("services")}</h2>
      {list(credits.services)}

      <h2>{t("media")}</h2>
      {list(credits.media)}

      <h2>{t("site")}</h2>
      <ul className="credits compact">
        {(siteLibs as Array<{ name: string; version: string; license: string; url: string }>).map((l) => (
          <li key={l.name}>
            <a href={l.url}>{l.name}</a> <span className="muted">{l.version}</span>
            {l.license && <span className="tag">{l.license}</span>}
          </li>
        ))}
      </ul>

      <h2>{t("license_title")}</h2>
      <p>{t("license_text")}</p>
      <p>
        <a className="button" href="https://github.com/mccoy88f/Zephyr_TV/blob/main/LICENSE">GPL-3.0</a>{" "}
        <a className="button" href="https://github.com/mccoy88f/Zephyr_TV">{t("source")}</a>
      </p>
    </section>
  );
}

import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { currentAccount } from "@/server/session";

const FEATURES = ["home", "watch", "addons", "player", "users", "phone"] as const;

/** The screenshots kept in English and Italian (docs/screens): the page's language, else English. */
function screen(locale: string, name: string) {
  return `/screens/${locale === "it" ? "it" : "en"}-${name}.jpg`;
}

export default async function Home({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("home");
  const account = await currentAccount().catch(() => null); // no database: the page still shows
  return (
    <>
      <section className="hero">
        <img className="hero-logo" src="/logo.png" alt="" width={112} height={112} />
        <h1>{t("hero_title")}</h1>
        <p className="lead">{t("hero_text")}</p>
        <div className="actions">
          <Link className="button primary" href="/download">{t("cta_download")}</Link>
          <a className="button" href="https://github.com/mccoy88f/Zephyr_TV">{t("cta_github")}</a>
        </div>
      </section>
      <section>
        <article className="card cta wide">
          <h2>{t("account_title")}</h2>
          <p>{t("account_text")}</p>
          <Link className="button primary" href={account ? "/account" : "/register"}>{account ? t("account_open") : t("cta_account")}</Link>
        </article>
      </section>
      <section>
        <img className="shot wide" src={screen(locale, "home-glass")} alt={t("screens_alt")} />
      </section>
      <section>
        <h2>{t("features_title")}</h2>
        <div className="grid">
          {FEATURES.map((f) => (
            <article key={f} className="card">
              <h3>{t(`f_${f}_title`)}</h3>
              <p>{t(`f_${f}_text`)}</p>
            </article>
          ))}
        </div>
      </section>
      <section>
        <h2>{t("screens_title")}</h2>
        <div className="shots">
          {["home-light", "home-dark", "app-menu", "settings", "phone"].map((s) => (
            <img key={s} className="shot" src={screen(locale, s)} alt={t("screens_alt")} loading="lazy" />
          ))}
        </div>
      </section>
    </>
  );
}

import { getFormatter, getTranslations, setRequestLocale } from "next-intl/server";
import { type Release, latestReleases } from "@/server/releases";

const SITE = "https://ztv.mccoy88f.link";
const RELEASES = "https://github.com/mccoy88f/Zephyr_TV/releases/latest/download";
const GUIDE = "https://github.com/mccoy88f/Zephyr_TV/blob/main/docs/receiver.md";

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

      {/* Zephyr TV Receiver (docs/receiver.md): for the TVs that can't run Zephyr TV, one card each. */}
      <h2 id="receiver">{t("receiver_title")}</h2>
      <p className="lead">{t("receiver_intro")}</p>
      <div className="grid">
        <article className="card release">
          <h3>{t("webos_title")}</h3>
          <p>{t("webos_text")}</p>
          <p className="muted receiver-label">{t("webos_repo")}</p>
          <code className="url">{`${SITE}/webos.json`}</code>
          <div>
            <a className="button primary" href={`${RELEASES}/ZephyrTVReceiver.ipk`}>{t("webos_ipk")}</a>{" "}
            <a className="button" href={`${GUIDE}#lg-webos`}>{t("receiver_guide")}</a>
          </div>
        </article>
        <article className="card release">
          <h3>{t("tizen_title")}</h3>
          <p>{t("tizen_text")}</p>
          <p className="muted receiver-label">{t("tizen_module")}</p>
          <code className="url">mccoy88f/Zephyr_TV</code>
          <div>
            <a className="button primary" href="https://github.com/reisxd/TizenBrew">{t("tizen_get")}</a>{" "}
            <a className="button" href={`${GUIDE}#samsung-tizen`}>{t("receiver_guide")}</a>
          </div>
        </article>
        <article className="card release">
          <h3>{t("vidaa_title")}</h3>
          <p>{t("vidaa_text")}</p>
          <p className="muted receiver-label">{t("vidaa_url")}</p>
          <code className="url">{`${SITE}/receiver/index.html`}</code>
          <p className="muted receiver-label">{t("vidaa_icon")}</p>
          <code className="url">{`${SITE}/receiver/icon.png`}</code>
          <div>
            <a className="button" href={`${GUIDE}#hisense-vidaa`}>{t("receiver_guide")}</a>
          </div>
        </article>
      </div>
    </section>
  );
}

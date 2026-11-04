import { getTranslations } from "next-intl/server";
import { browserLocale, onAndroid } from "@/server/locale";

/**
 * Open [ztv] (a ztv: link, "title/movie/tt…?n=…" without its scheme) in Zephyr TV, or get Zephyr TV. On Android an
 * intent: address, which Chrome opens in the app and, without it, sends to the download page instead.
 */
export async function OpenButtons({ ztv }: { ztv: string }) {
  const locale = await browserLocale();
  const t = await getTranslations({ locale, namespace: "share" });
  const site = process.env.SITE_URL || "https://ztv.mccoy88f.link";
  const download = `${site}/${locale}/download`;
  const open = (await onAndroid())
    ? `intent://${ztv}#Intent;scheme=ztv;package=link.mccoy88f.zephyr;S.browser_fallback_url=${encodeURIComponent(download)};end`
    : `ztv://${ztv}`;
  return (
    <>
      <div className="actions">
        <a className="button primary" href={open}>{t("open")}</a>
        <a className="button" href={download}>{t("get")}</a>
      </div>
      <p className="muted small">{t("hint")}</p>
    </>
  );
}

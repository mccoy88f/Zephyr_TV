import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { browserLocale } from "@/server/locale";
import { type TitleInfo, titleInfo } from "@/server/titles";
import { OpenButtons } from "../../../OpenButtons";

type Props = { params: Promise<{ type: string; id: string }>; searchParams: Promise<{ n?: string; s?: string; e?: string }> };

/** The title, its episode and the name the app sent (shown when the catalogs don't know it). */
async function load({ params, searchParams }: Props) {
  const { type, id: raw } = await params;
  const q = await searchParams;
  const id = decodeURIComponent(raw);
  const season = q.s && /^\d{1,3}$/.test(q.s) ? Number(q.s) : undefined;
  const episode = q.e && /^\d{1,4}$/.test(q.e) ? Number(q.e) : undefined;
  const locale = await browserLocale();
  const info = await titleInfo(type, id, locale, season, season != null ? episode : undefined);
  // The name the app sent first: it's in the language of who shared it (Cinemeta's are in English).
  const name = q.n?.trim().slice(0, 200) || info?.name || "Zephyr TV";
  const query = new URLSearchParams({ ...(q.n ? { n: q.n } : {}), ...(season != null && episode != null ? { s: String(season), e: String(episode) } : {}) });
  const ztv = `title/${type}/${encodeURIComponent(id)}${query.size ? `?${query}` : ""}`;
  return { type, id, info, name, season, episode, locale, ztv };
}

/** "Name · S1 · E3 · Episode name", or "Name (1997)". */
function heading(name: string, info: TitleInfo | null, t: (k: string, v?: Record<string, number>) => string, season?: number, episode?: number) {
  if (season != null && episode != null) {
    return [name, t("episode", { s: season, e: episode }), info?.episode?.name].filter(Boolean).join(" · ");
  }
  return info?.year ? `${name} (${info.year})` : name;
}

// What a chat shows for the link: the title, its plot and its poster (an episode's still), from the server.
export async function generateMetadata(props: Props): Promise<Metadata> {
  const { info, name, season, episode, locale } = await load(props);
  const t = await getTranslations({ locale, namespace: "share" });
  const title = heading(name, info, t, season, episode);
  const description = (info?.overview || t("watch")).slice(0, 280);
  const image = info?.episode?.still || info?.poster || info?.background || "/logo.png";
  const site = process.env.SITE_URL || "https://ztv.mccoy88f.link";
  return {
    metadataBase: new URL(site),
    title: `${title} · Zephyr TV`,
    description,
    openGraph: { siteName: "Zephyr TV", title, description, images: [image], type: "video.other" },
    twitter: { card: "summary_large_image", title, description, images: [image] },
    robots: { index: false },
  };
}

export default async function SharedTitle(props: Props) {
  const { info, name, season, episode, locale, ztv } = await load(props);
  const t = await getTranslations({ locale, namespace: "share" });
  const picture = info?.poster || info?.episode?.still;
  return (
    <article className="shared">
      {picture ? <img className="poster" src={picture} alt="" /> : <img className="poster logo" src="/logo.png" alt="" />}
      <div>
        <p className="muted">{t("watch")}</p>
        <h1>{heading(name, info, t, season, episode)}</h1>
        {info?.overview && <p className="overview">{info.overview}</p>}
        <OpenButtons ztv={ztv} />
      </div>
    </article>
  );
}

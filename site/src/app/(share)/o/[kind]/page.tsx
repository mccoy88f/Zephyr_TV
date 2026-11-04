import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { browserLocale } from "@/server/locale";
import { OpenButtons } from "../../OpenButtons";

const KINDS = ["ext", "addon", "nuvio"] as const;
type Props = { params: Promise<{ kind: string }>; searchParams: Promise<{ u?: string }> };

/** The address shared ([u]): only a web one is shown and passed on. */
function address(u?: string) {
  return u && /^https?:\/\//.test(u) && u.length < 2000 ? u : null;
}

export async function generateMetadata({ params, searchParams }: Props): Promise<Metadata> {
  const { kind } = await params;
  const t = await getTranslations({ locale: await browserLocale(), namespace: "share" });
  const u = address((await searchParams).u);
  const title = KINDS.includes(kind as (typeof KINDS)[number]) ? t(kind) : "Zephyr TV";
  return {
    title: `${title} · Zephyr TV`,
    description: u ? new URL(u).host : title,
    openGraph: { siteName: "Zephyr TV", title, description: u ? new URL(u).host : undefined, images: ["/logo.png"] },
    robots: { index: false },
  };
}

/** A shared extension, Stremio add-on or Nuvio repository: what it is, where it's from, and Open in Zephyr TV. */
export default async function SharedThing({ params, searchParams }: Props) {
  const { kind } = await params;
  if (!KINDS.includes(kind as (typeof KINDS)[number])) notFound();
  const u = address((await searchParams).u);
  if (!u) notFound();
  const t = await getTranslations({ locale: await browserLocale(), namespace: "share" });
  return (
    <article className="shared">
      <img className="poster logo" src="/logo.png" alt="" />
      <div>
        <p className="muted">Zephyr TV</p>
        <h1>{t(kind)}</h1>
        <p className="overview"><code>{new URL(u).host}</code></p>
        <OpenButtons ztv={`${kind}?u=${encodeURIComponent(u)}`} />
      </div>
    </article>
  );
}

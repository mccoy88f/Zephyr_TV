import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";
import { browserLocale } from "@/server/locale";

type Props = { searchParams: Promise<{ r?: string }> };

export const metadata: Metadata = { title: "Trakt · Zephyr TV", robots: { index: false } };

/** Where the phone ends after signing in to Trakt: done (the TV carries on by itself), refused, or gone wrong. */
export default async function TraktDone({ searchParams }: Props) {
  const r = (await searchParams).r;
  const result = r === "ok" || r === "refused" ? r : "error";
  const t = await getTranslations({ locale: await browserLocale(), namespace: "trakt" });
  return (
    <article className="shared">
      <img className="poster logo" src="/logo.png" alt="" />
      <div>
        <p className="muted">Trakt · Zephyr TV</p>
        <h1>{t(`${result}_title`)}</h1>
        <p className="overview">{t(`${result}_text`)}</p>
      </div>
    </article>
  );
}

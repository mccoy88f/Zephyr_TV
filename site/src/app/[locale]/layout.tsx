import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { hasLocale, NextIntlClientProvider } from "next-intl";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Header } from "@/components/Header";
import { Link } from "@/i18n/navigation";
import { routing } from "@/i18n/routing";
import "../globals.css";

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: "meta" });
  const site = process.env.SITE_URL || "https://ztv.mccoy88f.link";
  return {
    metadataBase: new URL(site),
    title: { default: t("title"), template: `%s · Zephyr TV` },
    description: t("description"),
    icons: { icon: "/logo.png", apple: "/logo.png" },
    openGraph: { siteName: "Zephyr TV", title: t("title"), description: t("description"), images: ["/logo.png"], type: "website" },
    alternates: { languages: Object.fromEntries(routing.locales.map((l) => [l, `/${l}`])) },
  };
}

export default async function LocaleLayout({ children, params }: { children: React.ReactNode; params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  if (!hasLocale(routing.locales, locale)) notFound();
  setRequestLocale(locale);
  const t = await getTranslations("footer");
  return (
    <html lang={locale}>
      <body>
        <NextIntlClientProvider>
          <Header />
          <main className="page">{children}</main>
          <footer className="footer">
            <p>
              Zephyr TV · <a href="https://github.com/mccoy88f/Zephyr_TV/blob/main/LICENSE">GPL-3.0</a> ·{" "}
              <a href="https://github.com/mccoy88f/Zephyr_TV">{t("source")}</a>
            </p>
            <p>
              <Link href="/credits">Credits</Link> · GitHub <a href="https://github.com/mccoy88f" rel="me">@mccoy88f</a> ·{" "}
              {t("projects")}: <a href="https://www.mccoy88f.link">www.mccoy88f.link</a>
            </p>
            <p className="muted">{t("tmdb")}</p>
          </footer>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}

import { NextIntlClientProvider } from "next-intl";
import { browserLocale } from "@/server/locale";
import "../globals.css";

/**
 * The shared links' pages (/t/…, /o/…): one address for everyone, so no language in it; each visitor gets theirs,
 * from their browser.
 */
export default async function ShareLayout({ children }: { children: React.ReactNode }) {
  const locale = await browserLocale();
  return (
    <html lang={locale}>
      <body>
        <NextIntlClientProvider locale={locale}>
          <main className="page share">{children}</main>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}

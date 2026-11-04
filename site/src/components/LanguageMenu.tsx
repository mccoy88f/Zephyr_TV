"use client";

import { useLocale } from "next-intl";
import { usePathname, useRouter } from "@/i18n/navigation";
import { LANGUAGE_NAMES, type Locale, routing } from "@/i18n/routing";

/** The page in another language: the same page, its prefix changed (remembered by next-intl's cookie). */
export function LanguageMenu({ label }: { label: string }) {
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();
  return (
    <select
      className="lang"
      aria-label={label}
      value={locale}
      onChange={(e) => router.replace(pathname, { locale: e.target.value as Locale })}
    >
      {routing.locales.map((l) => (
        <option key={l} value={l}>{LANGUAGE_NAMES[l]}</option>
      ))}
    </select>
  );
}

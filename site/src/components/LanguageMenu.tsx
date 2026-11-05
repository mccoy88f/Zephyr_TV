"use client";

import { useRef } from "react";
import { useLocale } from "next-intl";
import { usePathname, useRouter } from "@/i18n/navigation";
import { LANGUAGE_NAMES, type Locale, routing } from "@/i18n/routing";

/**
 * The page in another language, picked by its flag (flag-icons, MIT; the language's name as its label and tooltip):
 * the same page, its prefix changed (remembered by next-intl's cookie).
 */
export function LanguageMenu({ label }: { label: string }) {
  const locale = useLocale() as Locale;
  const pathname = usePathname();
  const router = useRouter();
  const menu = useRef<HTMLDetailsElement>(null);
  return (
    <details className="langs" ref={menu}>
      <summary aria-label={`${label}: ${LANGUAGE_NAMES[locale]}`} title={LANGUAGE_NAMES[locale]}>
        <img src={`/flags/${locale}.svg`} alt="" width={28} height={21} />
      </summary>
      <ul role="menu" aria-label={label}>
        {routing.locales.map((l) => (
          <li key={l} role="none">
            <button
              type="button"
              role="menuitem"
              className={l === locale ? "on" : undefined}
              title={LANGUAGE_NAMES[l]}
              aria-label={LANGUAGE_NAMES[l]}
              lang={l}
              onClick={() => {
                menu.current?.removeAttribute("open");
                if (l !== locale) router.replace(pathname, { locale: l });
              }}
            >
              <img src={`/flags/${l}.svg`} alt="" width={32} height={24} />
            </button>
          </li>
        ))}
      </ul>
    </details>
  );
}

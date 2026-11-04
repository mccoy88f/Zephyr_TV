import { defineRouting } from "next-intl/routing";

/** The site's languages: the same as Zephyr TV's (its res/values-*). Every page lives under one: /it/…, /en/…. */
export const routing = defineRouting({
  locales: ["en", "it", "es", "fr", "de", "pt", "ca", "zh", "hi"],
  defaultLocale: "en",
  localePrefix: "always",
});

export type Locale = (typeof routing.locales)[number];

/** Each language's name, in itself, for the language menu. */
export const LANGUAGE_NAMES: Record<Locale, string> = {
  en: "English",
  it: "Italiano",
  es: "Español",
  fr: "Français",
  de: "Deutsch",
  pt: "Português (Brasil)",
  ca: "Català",
  zh: "中文（简体）",
  hi: "हिन्दी",
};

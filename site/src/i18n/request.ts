import { hasLocale } from "next-intl";
import { getRequestConfig } from "next-intl/server";
import { routing } from "./routing";

// The language of the page asked for (its /xx/ prefix), and its messages; an unknown one gets English.
export default getRequestConfig(async ({ requestLocale }) => {
  const asked = await requestLocale;
  const locale = hasLocale(routing.locales, asked) ? asked : routing.defaultLocale;
  return { locale, messages: (await import(`../../messages/${locale}.json`)).default };
});

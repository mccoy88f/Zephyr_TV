import "server-only";
import { headers } from "next/headers";
import { type Locale, routing } from "@/i18n/routing";

/**
 * The visitor's language among the site's, from what their browser asks for ("pt-BR,pt;q=0.9,en;q=0.8": pt): for pages
 * whose address has no language, as a shared link's. English when none matches.
 */
export async function browserLocale(): Promise<Locale> {
  const asked = (await headers()).get("accept-language") ?? "";
  const wanted = asked.split(",")
    .map((part) => {
      const [tag, q] = part.trim().split(";q=");
      return { lang: tag.toLowerCase().split("-")[0], q: q ? Number(q) : 1 };
    })
    .filter((x) => x.lang && !Number.isNaN(x.q))
    .sort((a, b) => b.q - a.q);
  return (wanted.find((w) => (routing.locales as readonly string[]).includes(w.lang))?.lang as Locale) ?? routing.defaultLocale;
}

/** Whether the visitor is on Android: there a link opens Zephyr TV through an intent: address, Chrome's way. */
export async function onAndroid() {
  return /android/i.test((await headers()).get("user-agent") ?? "");
}

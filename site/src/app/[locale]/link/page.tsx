import { getTranslations, setRequestLocale } from "next-intl/server";
import { redirect } from "@/i18n/navigation";
import { normalizeCode, pendingLink } from "@/server/devices";
import { answerTv } from "@/server/profileActions";
import { currentAccount } from "@/server/session";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "link" }))("title"), robots: { index: false } };
}

/**
 * Where a TV's code is entered (or its QR code leads, the code in the address): signed in, the TV is named and linked
 * to the account with Allow, or refused.
 */
export default async function LinkTv({ params, searchParams }: {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ code?: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("link");
  const typed = (await searchParams).code ?? "";
  const code = normalizeCode(typed);
  const account = await currentAccount();
  if (!account) return redirect({ href: `/login?next=${encodeURIComponent(code ? `/link?code=${code}` : "/link")}`, locale });
  const tv = code ? await pendingLink(code) : null;

  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      {tv ? (
        <>
          <p className="lead">{t("ask", { name: tv.name })}</p>
          <p className="muted">{[tv.model, tv.appVersion && `Zephyr TV ${tv.appVersion}`].filter(Boolean).join(" · ")}</p>
          <p className="muted">{t("account", { email: account.email })}</p>
          <div className="actions left">
            <form action={answerTv}>
              <input type="hidden" name="code" value={code!} />
              <input type="hidden" name="allow" value="1" />
              <button className="button primary" type="submit">{t("allow")}</button>
            </form>
            <form action={answerTv}>
              <input type="hidden" name="code" value={code!} />
              <input type="hidden" name="allow" value="0" />
              <button className="button" type="submit">{t("deny")}</button>
            </form>
          </div>
        </>
      ) : (
        <>
          <p className="muted">{t("intro")}</p>
          {typed && <p className="error">{t("unknown")}</p>}
          <form className="form" method="get">
            <label>
              {t("code")}
              <input name="code" defaultValue={typed} placeholder="ABCD-EFGH" autoCapitalize="characters" autoComplete="off" required maxLength={12} />
            </label>
            <button className="button primary" type="submit">{t("next")}</button>
          </form>
        </>
      )}
    </section>
  );
}

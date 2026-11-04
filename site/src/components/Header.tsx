import { getTranslations } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { currentAccount } from "@/server/session";
import { LanguageMenu } from "./LanguageMenu";
import { signOut } from "./actions";

/** The top of every page: the logo, the pages, the language and who's signed in. */
export async function Header() {
  const t = await getTranslations("nav");
  const account = await currentAccount().catch(() => null); // no database: the site still shows
  return (
    <header className="header">
      <Link href="/" className="brand">
        <img src="/logo.png" alt="" width={36} height={36} />
        <span>Zephyr TV</span>
      </Link>
      <nav className="nav">
        <Link href="/download">{t("download")}</Link>
        <Link href="/credits">{t("credits")}</Link>
        {account?.role === "superadmin" && <Link href="/admin">{t("admin")}</Link>}
        {account && <Link href="/account">{t("account")}</Link>}
        {account ? (
          <form action={signOut}>
            <button className="linkish" type="submit">{t("logout")}</button>
          </form>
        ) : (
          <Link href="/login">{t("login")}</Link>
        )}
        <LanguageMenu label={t("language")} />
      </nav>
    </header>
  );
}

import { getFormatter, getTranslations, setRequestLocale } from "next-intl/server";
import { Form } from "@/components/Form";
import { redirect } from "@/i18n/navigation";
import { LANGUAGE_NAMES, routing } from "@/i18n/routing";
import { changeEmail, changePassword, deleteAccount, setLanguage, signOutOthers } from "@/server/accountActions";
import { currentAccount, sessionsOf } from "@/server/session";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "account" }))("title"), robots: { index: false } };
}

/**
 * The account signed in: its email, the language of its emails, its password and email, where it's signed in, and
 * deleting it. Its Zephyr TV users and TVs come here too (the next part of this phase).
 */
export default async function AccountPage({ params, searchParams }: {
  params: Promise<{ locale: string }>;
  searchParams: Promise<{ done?: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);
  const account = await currentAccount();
  if (!account) return redirect({ href: "/login", locale });
  const t = await getTranslations("account");
  const f = await getTranslations("fields");
  const format = await getFormatter();
  const done = (await searchParams).done;
  const list = await sessionsOf(account.id);

  return (
    <section>
      <h1>{t("title")}</h1>
      {done && ["email", "password", "sessions"].includes(done) && <p className="okmsg" role="status">{t(`done_${done}`)}</p>}
      <p className="muted">{t("signed_in", { email: account.email })} · {t("since", { date: format.dateTime(account.createdAt, { dateStyle: "long" }) })}</p>

      <div className="grid two">
        <article className="card">
          <h2>{t("language_title")}</h2>
          <p>{t("language_text")}</p>
          <form className="form" action={setLanguage}>
            <select className="lang" name="locale" defaultValue={account.locale}>
              {routing.locales.map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l]}</option>)}
            </select>
            <button className="button" type="submit">{t("save")}</button>
          </form>
        </article>

        <article className="card">
          <h2>{t("password_title")}</h2>
          <Form action={changePassword} submit={t("password_submit")} done={t("password_done")} fields={[
            { name: "current", label: f("current_password"), type: "password", autoComplete: "current-password" },
            { name: "password", label: f("new_password"), type: "password", autoComplete: "new-password" },
            { name: "password2", label: f("repeat_password"), type: "password", autoComplete: "new-password" },
          ]} />
        </article>

        <article className="card">
          <h2>{t("email_title")}</h2>
          <p>{t("email_text")}</p>
          <Form action={changeEmail} submit={t("email_submit")} done={t("email_done")} fields={[
            { name: "email", label: f("new_email"), type: "email", autoComplete: "email" },
            { name: "current", label: f("current_password"), type: "password", autoComplete: "current-password" },
          ]} />
        </article>

        <article className="card">
          <h2>{t("sessions_title")}</h2>
          <ul className="sessions">
            {list.map((s) => (
              <li key={s.tokenHash}>
                {s.mine && <span className="tag">{t("this_browser")}</span>}
                <span>{format.dateTime(s.createdAt, { dateStyle: "medium", timeStyle: "short" })}</span>
                <span className="muted small">{s.userAgent ?? ""}</span>
              </li>
            ))}
          </ul>
          {list.length > 1 && (
            <form action={signOutOthers}>
              <button className="button" type="submit">{t("sessions_others")}</button>
            </form>
          )}
        </article>
      </div>

      <article className="card danger-zone">
        <h2>{t("delete_title")}</h2>
        <p>{t("delete_text")}</p>
        <Form action={deleteAccount} danger submit={t("delete_submit")} fields={[
          { name: "current", label: f("current_password"), type: "password", autoComplete: "current-password" },
        ]} />
      </article>
    </section>
  );
}

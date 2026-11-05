"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { type FormState, register } from "@/server/accountActions";

/**
 * Sign up: email and password, and once sent the form gives way to what comes next, in view: the activation link
 * went to that email (open it, look in spam too), sending it again, signing in.
 */
export function RegisterForm() {
  const t = useTranslations("register");
  const f = useTranslations("fields");
  const forms = useTranslations("forms");
  const [state, run, pending] = useActionState<FormState, FormData>(register, {});
  if (state.ok)
    return (
      <article className="card cta sent" role="status">
        <div className="sent-icon" aria-hidden="true">✉</div>
        <h2>{t("done_title")}</h2>
        <p>{t.rich("done_text", { email: state.email ?? "", b: (c) => <strong className="sent-email">{c}</strong> })}</p>
        <p className="small">{t("done_spam")}</p>
        <div className="actions left">
          <Link className="button" href="/resend">{t("resend")}</Link>
          <Link className="button primary" href="/login">{t("done_login")}</Link>
        </div>
      </article>
    );
  return (
    <>
      <p className="muted">{t("intro")}</p>
      <form className="form" action={run}>
        <label>
          {f("email")}
          <input name="email" type="email" autoComplete="email" required maxLength={254} />
        </label>
        <label>
          {f("new_password")}
          <input name="password" type="password" autoComplete="new-password" required maxLength={256} />
        </label>
        <label>
          {f("repeat_password")}
          <input name="password2" type="password" autoComplete="new-password" required maxLength={256} />
        </label>
        {state.error && <p className="error" role="alert">{forms(state.error)}</p>}
        <button className="button primary" type="submit" disabled={pending}>{t("submit")}</button>
      </form>
      <p className="muted small">{t("rules")}</p>
      <p><Link href="/login">{t("have_account")}</Link> · <Link href="/resend">{t("resend")}</Link></p>
    </>
  );
}

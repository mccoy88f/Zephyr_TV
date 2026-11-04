"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import { type LoginState, login } from "./actions";

export function LoginForm() {
  const t = useTranslations("login");
  const [state, action, pending] = useActionState<LoginState, FormData>(login, {});
  return (
    <form className="form" action={action}>
      <label>
        {t("email")}
        <input name="email" type="email" autoComplete="email" required maxLength={254} />
      </label>
      <label>
        {t("password")}
        <input name="password" type="password" autoComplete="current-password" required />
      </label>
      {state.error && <p className="error" role="alert">{t(`error_${state.error}`)}</p>}
      <button className="button primary" type="submit" disabled={pending}>{t("submit")}</button>
    </form>
  );
}

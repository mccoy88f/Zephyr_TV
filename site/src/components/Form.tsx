"use client";

import { useActionState } from "react";
import { useTranslations } from "next-intl";
import type { FormState } from "@/server/accountActions";

export type Field = { name: string; label?: string; type?: string; autoComplete?: string; value?: string; hidden?: boolean; required?: boolean };

/**
 * A form posting to a server [action]: its [fields], what went wrong (a "forms" message) and, once done, [done]
 * (the form stays, for another go). [danger]: a red button, for what can't be undone.
 */
export function Form({ action, fields, submit, done, danger }: {
  action: (state: FormState, data: FormData) => Promise<FormState>;
  fields: Field[];
  submit: string;
  done?: string;
  danger?: boolean;
}) {
  const t = useTranslations("forms");
  const [state, run, pending] = useActionState(action, {});
  return (
    <form className="form" action={run}>
      {fields.map((f) =>
        f.hidden ? (
          <input key={f.name} type="hidden" name={f.name} value={f.value} />
        ) : (
          <label key={f.name}>
            {f.label}
            <input name={f.name} type={f.type ?? "text"} autoComplete={f.autoComplete} defaultValue={f.value} required={f.required ?? true} maxLength={f.type === "password" ? 256 : 254} />
          </label>
        ),
      )}
      {state.error && <p className="error" role="alert">{t(state.error)}</p>}
      {state.ok && done && <p className="okmsg" role="status">{done}</p>}
      <button className={`button ${danger ? "danger" : "primary"}`} type="submit" disabled={pending}>{submit}</button>
    </form>
  );
}

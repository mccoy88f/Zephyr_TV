import { getTranslations, setRequestLocale } from "next-intl/server";
import { Form } from "@/components/Form";
import { Link } from "@/i18n/navigation";
import { register } from "@/server/accountActions";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "register" }))("title") };
}

/** Sign up: email and password; the email confirmed by a link before the account works. */
export default async function Register({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("register");
  const f = await getTranslations("fields");
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <p className="muted">{t("intro")}</p>
      <Form action={register} submit={t("submit")} done={t("done")} fields={[
        { name: "email", label: f("email"), type: "email", autoComplete: "email" },
        { name: "password", label: f("new_password"), type: "password", autoComplete: "new-password" },
        { name: "password2", label: f("repeat_password"), type: "password", autoComplete: "new-password" },
      ]} />
      <p className="muted small">{t("rules")}</p>
      <p><Link href="/login">{t("have_account")}</Link> · <Link href="/resend">{t("resend")}</Link></p>
    </section>
  );
}

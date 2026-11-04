import { getTranslations, setRequestLocale } from "next-intl/server";
import { Form } from "@/components/Form";
import { resetPassword } from "@/server/accountActions";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "reset" }))("title"), robots: { index: false } };
}

/** A new password, from the link sent by email. */
export default async function Reset({ params, searchParams }: { params: Promise<{ locale: string }>; searchParams: Promise<{ token?: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("reset");
  const f = await getTranslations("fields");
  const token = (await searchParams).token ?? "";
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <Form action={resetPassword} submit={t("submit")} fields={[
        { name: "token", hidden: true, value: token },
        { name: "password", label: f("new_password"), type: "password", autoComplete: "new-password" },
        { name: "password2", label: f("repeat_password"), type: "password", autoComplete: "new-password" },
      ]} />
      <p className="muted small">{t("rules")}</p>
    </section>
  );
}

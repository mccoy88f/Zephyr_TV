import { getTranslations, setRequestLocale } from "next-intl/server";
import { Form } from "@/components/Form";
import { resend } from "@/server/accountActions";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "resend" }))("title") };
}

/** The confirmation link again, for an account not confirmed yet. */
export default async function Resend({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("resend");
  const f = await getTranslations("fields");
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <p className="muted">{t("intro")}</p>
      <Form action={resend} submit={t("submit")} done={t("done")} fields={[{ name: "email", label: f("email"), type: "email", autoComplete: "email" }]} />
    </section>
  );
}

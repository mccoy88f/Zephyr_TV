import { getTranslations, setRequestLocale } from "next-intl/server";
import { Form } from "@/components/Form";
import { forgot } from "@/server/accountActions";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "forgot" }))("title") };
}

/** A link to set a new password, by email. */
export default async function Forgot({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("forgot");
  const f = await getTranslations("fields");
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <p className="muted">{t("intro")}</p>
      <Form action={forgot} submit={t("submit")} done={t("done")} fields={[{ name: "email", label: f("email"), type: "email", autoComplete: "email" }]} />
    </section>
  );
}

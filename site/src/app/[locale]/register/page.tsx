import { getTranslations, setRequestLocale } from "next-intl/server";
import { RegisterForm } from "./RegisterForm";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "register" }))("title") };
}

/** Sign up: email and password; the email confirmed by a link before the account works (see RegisterForm). */
export default async function Register({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("register");
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <RegisterForm />
    </section>
  );
}

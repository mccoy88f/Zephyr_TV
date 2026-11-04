import { getTranslations, setRequestLocale } from "next-intl/server";
import { LoginForm } from "./LoginForm";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "login" }))("title") };
}

export default async function Login({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("login");
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <LoginForm />
      <p className="muted">{t("signup_soon")}</p>
    </section>
  );
}

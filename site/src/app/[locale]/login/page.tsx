import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
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
      <p>
        <Link href="/register">{t("register")}</Link> · <Link href="/forgot">{t("forgot")}</Link> · <Link href="/resend">{t("resend")}</Link>
      </p>
    </section>
  );
}

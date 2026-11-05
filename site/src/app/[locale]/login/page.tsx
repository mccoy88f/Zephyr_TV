import { getTranslations, setRequestLocale } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { LoginForm } from "./LoginForm";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "login" }))("title") };
}

export default async function Login({ params, searchParams }: { params: Promise<{ locale: string }>; searchParams: Promise<{ next?: string }> }) {
  const { locale } = await params;
  const next = (await searchParams).next;
  setRequestLocale(locale);
  const t = await getTranslations("login");
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <LoginForm next={next} />
      <p>
        <Link href="/register">{t("register")}</Link> · <Link href="/forgot">{t("forgot")}</Link> · <Link href="/resend">{t("resend")}</Link>
      </p>
    </section>
  );
}

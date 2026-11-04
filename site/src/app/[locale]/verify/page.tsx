import { getTranslations, setRequestLocale } from "next-intl/server";
import { Form } from "@/components/Form";
import { confirmLink } from "@/server/accountActions";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "verify" }))("title"), robots: { index: false } };
}

/**
 * Where an email's confirmation link leads: a button uses it (not opening the page, which mail scanners do too).
 */
export default async function Verify({ params, searchParams }: { params: Promise<{ locale: string }>; searchParams: Promise<{ token?: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("verify");
  const token = (await searchParams).token ?? "";
  return (
    <section className="narrow">
      <h1>{t("title")}</h1>
      <p className="muted">{t("intro")}</p>
      <Form action={confirmLink} submit={t("submit")} fields={[{ name: "token", hidden: true, value: token }]} />
    </section>
  );
}

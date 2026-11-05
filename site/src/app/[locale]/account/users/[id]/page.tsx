import { and, eq } from "drizzle-orm";
import { notFound } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { db } from "@/db";
import { profiles } from "@/db/schema";
import { Link, redirect } from "@/i18n/navigation";
import { STYLES, STYLE_NAMES, avatarUrl, newSeed, parseAvatar } from "@/lib/avatars";
import { saveProfile } from "@/server/profileActions";
import { currentAccount } from "@/server/session";

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  return { title: (await getTranslations({ locale, namespace: "users" }))("edit"), robots: { index: false } };
}

/**
 * A Zephyr TV user's page: their name, and their avatar picked among each style's (their own seed first, as the app's
 * styles page shows it, then a few new ones), or none.
 */
export default async function UserPage({ params, searchParams }: {
  params: Promise<{ locale: string; id: string }>;
  searchParams: Promise<{ saved?: string }>;
}) {
  const { locale, id } = await params;
  setRequestLocale(locale);
  const account = await currentAccount();
  if (!account) return redirect({ href: "/login", locale });
  if (!/^[0-9a-f-]{36}$/.test(id)) notFound();
  const [p] = await db.select().from(profiles).where(and(eq(profiles.id, id), eq(profiles.accountId, account.id)));
  if (!p) notFound();
  const t = await getTranslations("users");
  const mine = parseAvatar(p.avatar);
  const seed = mine?.seed ?? newSeed();

  return (
    <section>
      <p><Link href="/account#users">← {t("back")}</Link></p>
      <h1>{p.name}</h1>
      {(await searchParams).saved && <p className="okmsg" role="status">{t("saved")}</p>}

      <article className="card">
        <h2>{t("name")}</h2>
        <form className="search" action={saveProfile}>
          <input type="hidden" name="id" value={p.id} />
          <input name="name" defaultValue={p.name} maxLength={40} required aria-label={t("name")} />
          <button className="button" type="submit">{t("save")}</button>
        </form>
      </article>

      <article className="card">
        <h2>{t("avatar")}</h2>
        <p className="muted">{t("avatar_intro")}</p>
        {STYLES.map((style) => {
          const choices = [`${style}/${seed}`, ...Array.from({ length: 5 }, () => `${style}/${newSeed()}`)];
          return (
            <div key={style} className="avatars">
              <h3>{STYLE_NAMES[style]}</h3>
              <div className="avatar-row">
                {choices.map((a) => (
                  <form key={a} action={saveProfile}>
                    <input type="hidden" name="id" value={p.id} />
                    <input type="hidden" name="avatar" value={a} />
                    <button className={`avatar-pick${a === p.avatar ? " on" : ""}`} type="submit" aria-label={t("pick")}>
                      <img src={avatarUrl(a)!} alt="" width={72} height={72} loading="lazy" />
                    </button>
                  </form>
                ))}
              </div>
            </div>
          );
        })}
        {p.avatar && (
          <form action={saveProfile}>
            <input type="hidden" name="id" value={p.id} />
            <input type="hidden" name="avatar" value="" />
            <button className="button" type="submit">{t("no_avatar")}</button>
          </form>
        )}
        <p className="muted small">{t("dicebear")}</p>
      </article>
    </section>
  );
}

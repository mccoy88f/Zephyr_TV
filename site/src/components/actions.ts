"use server";

import { getLocale } from "next-intl/server";
import { redirect } from "@/i18n/navigation";
import { endSession } from "@/server/session";

/** Sign out: this browser's session ended, back to the home page. */
export async function signOut() {
  await endSession();
  redirect({ href: "/", locale: await getLocale() });
}

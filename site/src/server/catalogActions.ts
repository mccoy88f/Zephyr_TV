"use server";

import { and, count, eq } from "drizzle-orm";
import { getLocale } from "next-intl/server";
import { headers } from "next/headers";
import { db } from "@/db";
import { catalog } from "@/db/schema";
import { redirect } from "@/i18n/navigation";
import type { FormState } from "./accountActions";
import { Refused, manifestUrl, readManifest, syncOfficial } from "./catalog";
import { allow } from "./limit";
import { currentAccount } from "./session";

const uuid = /^[0-9a-f-]{36}$/;
const field = (data: FormData, name: string) => String(data.get(name) ?? "");
const MAX_PENDING = 5;

/**
 * An extension or repository proposed for the catalogue by the account signed in: read from its address and checked
 * first (as Zephyr TV would), then waiting for review. Already proposed: refused, whoever did. Five waiting at most.
 */
export async function submitExtension(_: FormState, data: FormData): Promise<FormState> {
  const account = await currentAccount();
  if (!account) return { error: "signed_out" };
  const ip = (await headers()).get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`submit:${account.id}`, 10, 3_600_000) || !allow(`submit:${ip}`, 20, 3_600_000)) return { error: "limited" };
  const url = manifestUrl(field(data, "url"));
  if (!url) return { error: "cat_address" };
  const [mine] = await db.select({ n: count() }).from(catalog).where(and(eq(catalog.submittedBy, account.id), eq(catalog.status, "pending")));
  if (mine.n >= MAX_PENDING) return { error: "cat_too_many" };
  const [known] = await db.select({ id: catalog.id }).from(catalog).where(eq(catalog.url, url));
  if (known) return { error: "cat_known" };
  try {
    const read = await readManifest(url);
    await db.insert(catalog).values({ ...read, note: field(data, "note").trim().slice(0, 1000) || null, submittedBy: account.id });
  } catch (e) {
    if (e instanceof Refused) return { error: `cat_${e.why}` };
    throw e;
  }
  return { ok: true };
}

/** A proposal of the account's own taken back, while it waits (or once refused). */
export async function withdrawExtension(data: FormData) {
  const account = await currentAccount();
  const id = field(data, "id");
  if (account && uuid.test(id))
    await db.delete(catalog).where(and(eq(catalog.id, id), eq(catalog.submittedBy, account.id), eq(catalog.status, "pending")));
  redirect({ href: "/extensions/submit", locale: await getLocale() });
}

/** The entry [data] names, if the one asking is the superadmin; else back to sign in. */
async function reviewed(data: FormData) {
  const me = await currentAccount();
  if (me?.role !== "superadmin") return redirect({ href: "/login", locale: await getLocale() });
  const id = field(data, "id");
  return uuid.test(id) ? id : null;
}

async function backToReview() {
  redirect({ href: "/admin/extensions", locale: await getLocale() });
}

/** In the catalogue (approved), or out of it: refused with a note for who proposed it, or removed. */
export async function reviewExtension(data: FormData) {
  const id = await reviewed(data);
  const status = field(data, "status");
  if (id && ["approved", "rejected", "removed", "pending"].includes(status))
    await db.update(catalog).set({
      status: status as "approved" | "rejected" | "removed" | "pending",
      review: field(data, "review").trim().slice(0, 1000) || null,
      reviewedAt: new Date(),
    }).where(eq(catalog.id, id));
  await backToReview();
}

/** Read again from its address: its name, version and the rest as they are now (an update since). */
export async function recheckExtension(data: FormData) {
  const id = await reviewed(data);
  if (id) {
    const [row] = await db.select().from(catalog).where(eq(catalog.id, id));
    const read = row ? await readManifest(row.url).catch(() => null) : null;
    if (read) await db.update(catalog).set({ ...read, checkedAt: new Date() }).where(eq(catalog.id, id));
  }
  await backToReview();
}

/** Gone from the site: as if never proposed (it can be proposed again). */
export async function deleteExtension(data: FormData) {
  const id = await reviewed(data);
  if (id) await db.delete(catalog).where(eq(catalog.id, id));
  await backToReview();
}

/** The official extensions read again now (see syncOfficial), not at the next hour. */
export async function refreshOfficial() {
  const me = await currentAccount();
  if (me?.role !== "superadmin") return redirect({ href: "/login", locale: await getLocale() });
  await syncOfficial().catch((e) => console.warn("[catalog] official extensions failed:", e instanceof Error ? e.message : e));
  await backToReview();
}


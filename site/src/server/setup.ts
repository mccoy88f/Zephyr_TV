import path from "node:path";
import { eq } from "drizzle-orm";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { db } from "@/db";
import { accounts } from "@/db/schema";
import { hashPassword, verifyPassword } from "./password";

/**
 * As the server starts: the database brought up to date (drizzle/), then the superadmin made from the environment
 * (SUPERADMIN_EMAIL and SUPERADMIN_PASSWORD): created the first time, its password set again when the variable
 * changes. Without SUPERADMIN_PASSWORD there's no superadmin, and the log says so.
 */
export async function setup() {
  await migrate(db, { migrationsFolder: path.join(process.cwd(), "drizzle") });
  const email = (process.env.SUPERADMIN_EMAIL || "su@ztv.mccoy88f.link").trim().toLowerCase();
  const password = process.env.SUPERADMIN_PASSWORD;
  if (!password) return console.warn("SUPERADMIN_PASSWORD is not set: no superadmin");
  const [found] = await db.select().from(accounts).where(eq(accounts.email, email));
  if (!found) {
    await db.insert(accounts).values({ email, passwordHash: await hashPassword(password), role: "superadmin", emailVerifiedAt: new Date() });
    return console.log(`superadmin ${email} created`);
  }
  const same = await verifyPassword(found.passwordHash, password);
  await db.update(accounts)
    .set({ role: "superadmin", blockedAt: null, emailVerifiedAt: found.emailVerifiedAt ?? new Date(), ...(same ? {} : { passwordHash: await hashPassword(password) }) })
    .where(eq(accounts.id, found.id));
  if (!same) console.log(`superadmin ${email}: password updated from the environment`);
}

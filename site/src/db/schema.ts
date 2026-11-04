import { index, pgEnum, pgTable, text, timestamp, uuid } from "drizzle-orm/pg-core";

/** What an account may do: a user's own things, or everything (the superadmin's panel). */
export const role = pgEnum("role", ["user", "superadmin"]);

/**
 * An account on the site: the main account its Zephyr TV users and TVs belong to. Signed in with its email and a
 * password (kept as an Argon2id hash only); usable once its email is verified.
 */
export const accounts = pgTable("accounts", {
  id: uuid("id").primaryKey().defaultRandom(),
  email: text("email").notNull().unique(), // lower case
  passwordHash: text("password_hash").notNull(),
  role: role("role").notNull().default("user"),
  locale: text("locale").notNull().default("en"),
  emailVerifiedAt: timestamp("email_verified_at", { withTimezone: true }),
  blockedAt: timestamp("blocked_at", { withTimezone: true }),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
});

/**
 * A signed-in browser: its cookie holds a random token, the table only that token's SHA-256 (a leaked table opens no
 * session). Ends at [expiresAt], on sign out, or when the account's password changes.
 */
export const sessions = pgTable(
  "sessions",
  {
    tokenHash: text("token_hash").primaryKey(),
    accountId: uuid("account_id").notNull().references(() => accounts.id, { onDelete: "cascade" }),
    userAgent: text("user_agent"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
  },
  (t) => [index("sessions_account").on(t.accountId)],
);

export type Account = typeof accounts.$inferSelect;

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

/** What a link sent by email does: confirm an account's email, set a new password, or move to a new email. */
export const emailPurpose = pgEnum("email_purpose", ["verify", "reset", "change"]);

/**
 * A link sent by email: its random token's SHA-256 only (the token is in the email), what it does, for which account,
 * until when. Used once. [newEmail]: where an email change goes.
 */
export const emailTokens = pgTable(
  "email_tokens",
  {
    tokenHash: text("token_hash").primaryKey(),
    accountId: uuid("account_id").notNull().references(() => accounts.id, { onDelete: "cascade" }),
    purpose: emailPurpose("purpose").notNull(),
    newEmail: text("new_email"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
  },
  (t) => [index("email_tokens_account").on(t.accountId)],
);

/** Where a Trakt sign-in is: the phone on Trakt's page, done (its code waiting for the TV), or refused. */
export const traktStatus = pgEnum("trakt_status", ["waiting", "done", "refused"]);

/**
 * A Trakt sign-in begun on a TV (see src/app/traktauth): [state] is the random one in the TV's QR code. Once the phone
 * has signed in, Trakt's code waits here for the TV, which asks with that state (api/trakt/poll) and gets it once:
 * then, or after 15 minutes, the row goes. Only a code: it's worth nothing without the TV's PKCE verifier, which never
 * leaves the TV, so the tokens never pass through the site.
 */
export const traktSignins = pgTable("trakt_signins", {
  state: text("state").primaryKey(),
  status: traktStatus("status").notNull().default("waiting"),
  code: text("code"),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
});

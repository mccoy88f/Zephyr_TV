import { index, integer, pgEnum, pgTable, text, timestamp, uuid } from "drizzle-orm/pg-core";

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

/**
 * A Zephyr TV user of an account (Mario, Anna…): their name and avatar (DiceBear's "style/seed", as Zephyr TV keeps
 * it), in the account's order. What each watched comes with sync (F4).
 */
export const profiles = pgTable(
  "profiles",
  {
    id: uuid("id").primaryKey().defaultRandom(),
    accountId: uuid("account_id").notNull().references(() => accounts.id, { onDelete: "cascade" }),
    name: text("name").notNull(),
    avatar: text("avatar"),
    position: integer("position").notNull().default(0),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [index("profiles_account").on(t.accountId)],
);

/**
 * A TV linked to an account: its name, model and Zephyr TV version as it said, and its token's SHA-256 (the TV keeps
 * the token). Unlinked from the account's page, or by the TV.
 */
export const devices = pgTable(
  "devices",
  {
    id: uuid("id").primaryKey().defaultRandom(),
    accountId: uuid("account_id").notNull().references(() => accounts.id, { onDelete: "cascade" }),
    tokenHash: text("token_hash").notNull().unique(),
    name: text("name").notNull(),
    model: text("model"),
    appVersion: text("app_version"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    lastSeenAt: timestamp("last_seen_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [index("devices_account").on(t.accountId)],
);

/** Where a TV's link is: waiting for someone to enter its code, allowed, or refused. */
export const linkStatus = pgEnum("link_status", ["pending", "approved", "denied"]);

/**
 * A TV asking to be linked: the short code it shows ([userCode], typed or scanned on a phone), the SHA-256 of the long
 * one it keeps to ask with ([deviceCodeHash]), and once allowed, the account. Ten minutes, then gone.
 */
export const linkCodes = pgTable("link_codes", {
  userCode: text("user_code").primaryKey(),
  deviceCodeHash: text("device_code_hash").notNull().unique(),
  status: linkStatus("status").notNull().default("pending"),
  accountId: uuid("account_id").references(() => accounts.id, { onDelete: "cascade" }),
  name: text("name").notNull(),
  model: text("model"),
  appVersion: text("app_version"),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
  expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
});


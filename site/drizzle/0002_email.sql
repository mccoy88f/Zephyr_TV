CREATE TYPE "public"."email_purpose" AS ENUM('verify', 'reset', 'change');--> statement-breakpoint
CREATE TABLE "email_tokens" (
	"token_hash" text PRIMARY KEY NOT NULL,
	"account_id" uuid NOT NULL,
	"purpose" "email_purpose" NOT NULL,
	"new_email" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"expires_at" timestamp with time zone NOT NULL
);
--> statement-breakpoint
ALTER TABLE "email_tokens" ADD CONSTRAINT "email_tokens_account_id_accounts_id_fk" FOREIGN KEY ("account_id") REFERENCES "public"."accounts"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "email_tokens_account" ON "email_tokens" USING btree ("account_id");
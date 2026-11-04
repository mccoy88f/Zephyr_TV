CREATE TYPE "public"."trakt_status" AS ENUM('waiting', 'done', 'refused');--> statement-breakpoint
CREATE TABLE "trakt_signins" (
	"state" text PRIMARY KEY NOT NULL,
	"status" "trakt_status" DEFAULT 'waiting' NOT NULL,
	"tokens" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);

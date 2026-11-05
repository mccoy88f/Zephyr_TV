CREATE TYPE "public"."catalog_kind" AS ENUM('extension', 'repository');--> statement-breakpoint
CREATE TYPE "public"."catalog_status" AS ENUM('pending', 'approved', 'rejected', 'removed');--> statement-breakpoint
CREATE TABLE "catalog" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"url" text NOT NULL,
	"kind" "catalog_kind" NOT NULL,
	"ext_id" text,
	"name" text NOT NULL,
	"description" text,
	"author" text,
	"icon" text,
	"version" text,
	"count" integer,
	"adult" boolean DEFAULT false NOT NULL,
	"homepage" text,
	"note" text,
	"status" "catalog_status" DEFAULT 'pending' NOT NULL,
	"review" text,
	"submitted_by" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"checked_at" timestamp with time zone DEFAULT now() NOT NULL,
	"reviewed_at" timestamp with time zone,
	CONSTRAINT "catalog_url_unique" UNIQUE("url")
);
--> statement-breakpoint
ALTER TABLE "catalog" ADD CONSTRAINT "catalog_submitted_by_accounts_id_fk" FOREIGN KEY ("submitted_by") REFERENCES "public"."accounts"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "catalog_status" ON "catalog" USING btree ("status");--> statement-breakpoint
CREATE INDEX "catalog_by" ON "catalog" USING btree ("submitted_by");
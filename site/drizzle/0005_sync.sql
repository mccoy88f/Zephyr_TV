CREATE TABLE "sync_docs" (
	"account_id" uuid NOT NULL,
	"key" text NOT NULL,
	"rev" integer NOT NULL,
	"data" text NOT NULL,
	"device_id" uuid,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "sync_docs_account_id_key_pk" PRIMARY KEY("account_id","key")
);
--> statement-breakpoint
ALTER TABLE "sync_docs" ADD CONSTRAINT "sync_docs_account_id_accounts_id_fk" FOREIGN KEY ("account_id") REFERENCES "public"."accounts"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "sync_docs" ADD CONSTRAINT "sync_docs_device_id_devices_id_fk" FOREIGN KEY ("device_id") REFERENCES "public"."devices"("id") ON DELETE set null ON UPDATE no action;
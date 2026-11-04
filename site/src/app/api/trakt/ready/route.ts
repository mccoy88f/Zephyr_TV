import { trakt } from "@/server/trakt";

export const dynamic = "force-dynamic";

/** Whether this site signs in to Trakt (its secret set): Zephyr TV asks before it shows the sign-in's QR code here. */
export function GET() {
  return trakt.secret ? Response.json({ ok: true }) : Response.json({ ok: false }, { status: 503 });
}

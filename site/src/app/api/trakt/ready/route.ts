export const dynamic = "force-dynamic";

/**
 * This site signs in to Trakt with PKCE (see traktauth): Zephyr TV asks before it shows the sign-in's QR code here,
 * and goes through the older site while this one isn't up.
 */
export function GET() {
  return Response.json({ ok: true, pkce: true });
}

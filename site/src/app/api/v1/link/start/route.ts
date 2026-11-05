import { allow } from "@/server/limit";
import { LINK_MINUTES, startLink } from "@/server/devices";

export const dynamic = "force-dynamic";

/**
 * A TV asking to be linked to an account ({name, model, version}): the code it shows and the address to enter it at
 * (with the code, for a QR code), and the long code it keeps to ask with (api/v1/link/poll), every [interval] seconds.
 */
export async function POST(req: Request) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`link:${ip}`, 10, 10 * 60_000)) return new Response(null, { status: 429 });
  const body = await req.json().catch(() => ({}));
  const { deviceCode, userCode } = await startLink(body?.name, body?.model, body?.version);
  const site = process.env.SITE_URL || "https://ztv.mccoy88f.link";
  return Response.json({
    device_code: deviceCode,
    user_code: userCode,
    verification_url: `${site}/link`,
    verification_url_complete: `${site}/link?code=${userCode}`,
    expires_in: LINK_MINUTES * 60,
    interval: 5,
  });
}

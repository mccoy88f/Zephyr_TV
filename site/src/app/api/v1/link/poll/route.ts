import { allow } from "@/server/limit";
import { pollLink } from "@/server/devices";

export const dynamic = "force-dynamic";

/**
 * The TV asking how its link stands ({device_code}): 202 while it waits, 200 with its token (given once: the TV keeps
 * it, for every call after) and the account's email once allowed, 410 when refused, 404 when unknown or too old.
 */
export async function POST(req: Request) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`linkpoll:${ip}`, 240, 10 * 60_000)) return new Response(null, { status: 429 });
  const code = (await req.json().catch(() => ({})))?.device_code;
  const r = await pollLink(code);
  if (r.state === "pending") return new Response(null, { status: 202 });
  if (r.state === "denied") return new Response(null, { status: 410 });
  if (r.state === "gone") return new Response(null, { status: 404 });
  return Response.json({ token: r.token, email: r.account.email });
}

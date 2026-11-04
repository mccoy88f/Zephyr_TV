import { allow } from "@/server/limit";
import { trakt, traktToken } from "@/server/trakt";

export const dynamic = "force-dynamic";

/**
 * A TV's Trakt tokens refreshed ({refresh_token}), with the app's secret: Trakt's answer and status as they are (200
 * new tokens; 400 or 401 when the refresh token is no longer good: the TV signs the user out).
 */
export async function POST(req: Request) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`refresh:${ip}`, 60, 10 * 60_000)) return new Response(null, { status: 429 });
  if (!trakt.secret) return new Response(null, { status: 503 });
  const refresh = (await req.json().catch(() => ({})))?.refresh_token;
  if (typeof refresh !== "string" || refresh.length < 10 || refresh.length > 512) return new Response(null, { status: 400 });
  const { status, json } = await traktToken({ refresh_token: refresh, grant_type: "refresh_token" });
  return Response.json(json, { status });
}

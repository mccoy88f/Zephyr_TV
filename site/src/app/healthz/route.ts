import { pool } from "@/db";

export const dynamic = "force-dynamic";

/** For Coolify and Docker's health check: 200 when the database answers, 503 when it doesn't. */
export async function GET() {
  try {
    await pool.query("select 1");
    return Response.json({ ok: true });
  } catch {
    return Response.json({ ok: false, error: "database" }, { status: 503 });
  }
}

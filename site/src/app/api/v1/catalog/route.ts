import { asc, desc, eq } from "drizzle-orm";
import { db } from "@/db";
import { catalog } from "@/db/schema";

export const dynamic = "force-dynamic";

/**
 * The extensions' catalogue for Zephyr TV: the approved extensions and repositories, by name. Each installed from its
 * [url] as any other (the TV reads and checks the manifest itself; nothing here is trusted in its place).
 */
export async function GET() {
  const list = await db.select().from(catalog).where(eq(catalog.status, "approved")).orderBy(desc(catalog.official), asc(catalog.name));
  return Response.json(
    {
      extensions: list.map((e) => ({
        url: e.url, kind: e.kind, id: e.extId, name: e.name, description: e.description, author: e.author,
        icon: e.icon, version: e.version, count: e.count, adult: e.adult, official: e.official,
      })),
    },
    { headers: { "cache-control": "public, max-age=300" } },
  );
}

// Runs once as the server starts (Next.js calls it): the database migrated and the superadmin made (see setup).
export async function register() {
  if (process.env.NEXT_RUNTIME !== "nodejs") return;
  if (!process.env.DATABASE_URL) return console.warn("DATABASE_URL is not set: no database");
  const { setup, tidyEveryHour } = await import("./server/setup");
  await setup();
  tidyEveryHour();
}

import { defineConfig } from "drizzle-kit";

// `npm run db:generate` writes the SQL migrations into drizzle/ from src/db/schema.ts; the server applies them as it
// starts (see src/server/setup.ts).
export default defineConfig({
  dialect: "postgresql",
  schema: "./src/db/schema.ts",
  out: "./drizzle",
});

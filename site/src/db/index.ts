import { drizzle } from "drizzle-orm/node-postgres";
import { Pool } from "pg";
import * as schema from "./schema";

// One pool for the server's life (and one only in development, where modules reload).
const global = globalThis as unknown as { ztvPool?: Pool };

export const pool = global.ztvPool ?? new Pool({ connectionString: process.env.DATABASE_URL, max: 10 });
if (process.env.NODE_ENV !== "production") global.ztvPool = pool;

export const db = drizzle(pool, { schema });

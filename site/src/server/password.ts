import { hash, verify } from "@node-rs/argon2";

// Argon2id (the library's default) with OWASP's recommended cost: 19 MiB, 2 passes.
const OPTIONS = { memoryCost: 19456, timeCost: 2, parallelism: 1 };

export function hashPassword(password: string) {
  return hash(password, OPTIONS);
}

/** Whether [password] is the one [stored] (an Argon2 hash) was made from. False for a hash it can't read. */
export async function verifyPassword(stored: string, password: string) {
  try {
    return await verify(stored, password);
  } catch {
    return false;
  }
}

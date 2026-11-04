import "server-only";
import { createHash } from "node:crypto";

/** What's wrong with a new password, or null: too short, too long, or among the leaked ones. */
export type PasswordProblem = "short" | "long" | "leaked" | null;

/**
 * A new password checked: 10 to 256 characters, and not among those leaked in data breaches. That's asked of Have I
 * Been Pwned with k-anonymity: only the first 5 characters of its SHA-1 leave the server, never the password. When it
 * can't be asked, the length alone counts.
 */
export async function checkNewPassword(password: string): Promise<PasswordProblem> {
  if (password.length < 10) return "short";
  if (password.length > 256) return "long";
  const sha1 = createHash("sha1").update(password).digest("hex").toUpperCase();
  try {
    const res = await fetch(`https://api.pwnedpasswords.com/range/${sha1.slice(0, 5)}`, {
      headers: { "Add-Padding": "true", "User-Agent": "ztv-site" },
      signal: AbortSignal.timeout(4000),
      cache: "no-store",
    });
    if (!res.ok) return null;
    const rest = sha1.slice(5);
    const leaked = (await res.text()).split("\n").some((line) => {
      const [suffix, count] = line.trim().split(":");
      return suffix === rest && Number(count) > 0;
    });
    return leaked ? "leaked" : null;
  } catch {
    return null;
  }
}

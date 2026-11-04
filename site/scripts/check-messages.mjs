// Every language has the same messages as English (messages/en.json): none missing, none left over, as the app's
// strings.xml are kept. Fails the build otherwise.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const dir = join(import.meta.dirname, "..", "messages");
const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (v && typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
const en = new Set(keys(JSON.parse(readFileSync(join(dir, "en.json"), "utf8"))));
let bad = 0;
for (const f of readdirSync(dir).filter((f) => f.endsWith(".json") && f !== "en.json")) {
  const have = new Set(keys(JSON.parse(readFileSync(join(dir, f), "utf8"))));
  const missing = [...en].filter((k) => !have.has(k));
  const extra = [...have].filter((k) => !en.has(k));
  if (missing.length || extra.length) {
    bad++;
    console.error(`${f}: missing ${missing.join(", ") || "none"}; extra ${extra.join(", ") || "none"}`);
  }
}
if (bad) process.exit(1);
console.log(`messages: ${en.size} keys, every language complete`);

// Before the build: the site's own libraries (package.json's dependencies), each with its version and licence as its
// package says, into content/site-licenses.json, which the Credits page lists. Run by `npm run build`.
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const root = join(import.meta.dirname, "..");
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8"));
const out = Object.keys(pkg.dependencies).sort().map((name) => {
  const p = JSON.parse(readFileSync(join(root, "node_modules", name, "package.json"), "utf8"));
  const license = typeof p.license === "string" ? p.license : p.license?.type ?? "";
  const repo = typeof p.repository === "string" ? p.repository : p.repository?.url ?? "";
  const url = p.homepage || repo.replace(/^git\+/, "").replace(/\.git$/, "").replace(/^git:/, "https:");
  return { name, version: p.version, license, url };
});
writeFileSync(join(root, "content", "site-licenses.json"), JSON.stringify(out, null, 2) + "\n");
console.log(`site-licenses: ${out.length} libraries`);

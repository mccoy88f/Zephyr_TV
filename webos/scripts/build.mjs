import * as esbuild from "esbuild";
import { cpSync, mkdirSync, rmSync } from "node:fs";

rmSync("dist", { recursive: true, force: true });
mkdirSync("dist", { recursive: true });

await esbuild.build({
  entryPoints: ["src/main.js"],
  bundle: true,
  splitting: true,
  format: "esm",
  target: "chrome79",
  outdir: "dist",
  sourcemap: false,
  legalComments: "none",
  chunkNames: "chunks/[name]-[hash]",
  define: { "process.env.NODE_ENV": '"production"' },
});

cpSync("src/index.html", "dist/index.html");
cpSync("src/app.css", "dist/app.css");
cpSync("appinfo.json", "dist/appinfo.json");
cpSync("icon.png", "dist/icon.png");
cpSync("largeIcon.png", "dist/largeIcon.png");

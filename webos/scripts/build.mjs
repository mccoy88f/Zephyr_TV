import { transformSync } from "@babel/core";
import * as esbuild from "esbuild";
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";

rmSync("dist", { recursive: true, force: true });
mkdirSync("dist", { recursive: true });

const bundled = await esbuild.build({
  entryPoints: ["src/main.js"],
  bundle: true,
  format: "iife",
  target: "es2020",
  write: false,
  legalComments: "none",
  define: { "process.env.NODE_ENV": '"production"' },
});

const lowered = transformSync(bundled.outputFiles[0].text, {
  babelrc: false,
  configFile: false,
  comments: false,
  presets: [["@babel/preset-env", { targets: { chrome: "38" } }]],
});

let code = lowered.code;
if (code.includes("regeneratorRuntime")) {
  code = readFileSync("node_modules/regenerator-runtime/runtime.js", "utf8") + "\n" + code;
}
writeFileSync("dist/main.js", code);

function downlevel(from, to) {
  const out = transformSync(readFileSync(from, "utf8"), {
    babelrc: false,
    configFile: false,
    comments: false,
    compact: true,
    presets: [["@babel/preset-env", { targets: { chrome: "38" } }]],
  });
  let text = out.code;
  if (text.includes("regeneratorRuntime")) {
    text = readFileSync("node_modules/regenerator-runtime/runtime.js", "utf8") + "\n" + text;
  }
  writeFileSync(to, text);
}

downlevel("node_modules/shaka-player/dist/shaka-player.compiled.js", "dist/shaka-player.compiled.js");
cpSync("node_modules/hls.js/dist/hls.js", "dist/hls.js");

cpSync("src/index.html", "dist/index.html");
cpSync("src/app.css", "dist/app.css");
cpSync("appinfo.json", "dist/appinfo.json");
cpSync("icon.png", "dist/icon.png");
cpSync("largeIcon.png", "dist/largeIcon.png");

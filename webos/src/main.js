import { boot } from "./app.js";

boot().catch((e) => {
  const app = document.getElementById("app");
  if (app) app.textContent = e && e.message ? e.message : String(e);
});

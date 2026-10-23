import { cpSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
const out = root + "dist/organizer";
const api =
  process.env.ORGANIZER_API_BASE || "https://tickit-mpy2.onrender.com";
const url = new URL(api);
if (
  (url.protocol !== "https:" &&
    !(
      url.protocol === "http:" &&
      ["localhost", "127.0.0.1"].includes(url.hostname)
    )) ||
  url.username ||
  url.password
)
  throw new Error("Organizer API must be HTTPS");
rmSync(out, { recursive: true, force: true });
mkdirSync(out + "/js", { recursive: true });
cpSync(root + "src/main/resources/static/organizer", out, { recursive: true });
cpSync(root + "src/main/resources/static/js/auth.js", out + "/js/auth.js");
writeFileSync(
  out + "/config.js",
  "window.TICKIT_API_BASE=" + JSON.stringify(url.origin) + ";\n",
);
console.log("Organizer static frontend built: dist/organizer");

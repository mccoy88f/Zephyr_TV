import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

// The messages of each language (messages/*.json), loaded per request (see src/i18n/request.ts).
const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");

const config: NextConfig = {
  // One folder with the server and only what it needs: the Docker image copies just that (see Dockerfile).
  output: "standalone",
  poweredByHeader: false,
  // Native or Node-only: left to Node, not bundled.
  serverExternalPackages: ["@node-rs/argon2", "pg", "nodemailer"],
  images: { unoptimized: true },
  // The devices' API from Zephyr TV Reviver too (webos/), a web app on the TV: from any origin, as it sends its token in
  // Authorization (never a cookie), so another site can do nothing with it a TV couldn't.
  async headers() {
    return [{
      source: "/api/v1/:path*",
      headers: [
        { key: "Access-Control-Allow-Origin", value: "*" },
        { key: "Access-Control-Allow-Methods", value: "GET, POST, DELETE, OPTIONS" },
        { key: "Access-Control-Allow-Headers", value: "Authorization, Content-Type, X-Zephyr-Version" },
        { key: "Access-Control-Max-Age", value: "86400" },
      ],
    }];
  },
};

export default withNextIntl(config);

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
};

export default withNextIntl(config);

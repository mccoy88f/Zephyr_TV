import createMiddleware from "next-intl/middleware";
import { routing } from "./i18n/routing";

// A page without its language (/, /download) goes to the visitor's: the one they picked last, else their browser's.
export default createMiddleware(routing);

// Not the API, the health check, the shared links (/t/, /o/: one address for every language), Next's own files, nor
// files with an extension (the logo, the screenshots).
export const config = {
  matcher: ["/((?!api|healthz|t/|o/|_next|.*\\..*).*)"],
};

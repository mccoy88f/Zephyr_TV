import { createNavigation } from "next-intl/navigation";
import { routing } from "./routing";

/** Links and redirects that keep the page's language. */
export const { Link, redirect, usePathname, useRouter, getPathname } = createNavigation(routing);

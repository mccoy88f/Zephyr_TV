import "server-only";

export type Release = { version: string; date: string; url: string; apk: string | null; beta: boolean; notes: string[] };

/**
 * The newest release and the newest beta on GitHub (GITHUB_REPO), each with its APK and its notes (the "- " lines of
 * its CHANGELOG section, without ** marks, as Zephyr TV shows them). Asked at most every 10 minutes; null when GitHub
 * can't be asked.
 */
export async function latestReleases(): Promise<{ stable: Release | null; beta: Release | null } | null> {
  const repo = process.env.GITHUB_REPO || "mccoy88f/Zephyr_TV";
  const headers: Record<string, string> = { Accept: "application/vnd.github+json", "User-Agent": "ztv-site" };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=30`, { headers, next: { revalidate: 600 } });
    if (!res.ok) return null;
    const list = (await res.json()) as Array<{
      tag_name: string; draft: boolean; prerelease: boolean; published_at: string; html_url: string; body: string | null;
      assets: Array<{ name: string; browser_download_url: string }>;
    }>;
    const all = list.filter((r) => !r.draft).map((r): Release => ({
      version: r.tag_name.replace(/^v/, ""),
      date: r.published_at,
      url: r.html_url,
      apk: r.assets.find((a) => a.name === "ZephyrTV.apk")?.browser_download_url ?? null,
      beta: r.prerelease,
      notes: (r.body ?? "").replace(/\r/g, "").split("\n").filter((l) => l.startsWith("- ")).map((l) => l.slice(2).replace(/\*\*/g, "").trim()),
    }));
    return { stable: all.find((r) => !r.beta) ?? null, beta: all.find((r) => r.beta) ?? null };
  } catch {
    return null;
  }
}

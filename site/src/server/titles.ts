import "server-only";

/** What a shared title's page shows, and its link's preview: name, year, plot, poster, backdrop; an episode's too. */
export type TitleInfo = {
  name: string;
  year?: string;
  overview?: string;
  poster?: string;
  background?: string;
  episode?: { season: number; episode: number; name?: string; still?: string };
};

const DAY = 86_400;

/**
 * Title [id] of [type] ("movie", "series"): from Cinemeta (Stremio's free catalog, IMDb ids "tt…"), or TMDB for
 * "tmdb:<number>" ids when TMDB_API_KEY is set (in [locale]'s language). With [season] and [episode], that episode's
 * name and still too. Null when neither knows it. Each answer is kept for a day.
 */
export async function titleInfo(type: string, id: string, locale: string, season?: number, episode?: number): Promise<TitleInfo | null> {
  if (!/^(movie|series|tv)$/.test(type) || !/^[A-Za-z0-9:._-]{1,64}$/.test(id)) return null;
  if (id.startsWith("tmdb:")) return tmdb(type, id.slice(5), locale, season, episode);
  return cinemeta(type === "tv" ? "series" : type, id, season, episode);
}

async function json(url: string, headers: Record<string, string> = {}) {
  try {
    const res = await fetch(url, { headers: { "User-Agent": "ztv-site", ...headers }, next: { revalidate: DAY } });
    return res.ok ? await res.json() : null;
  } catch {
    return null;
  }
}

async function cinemeta(type: string, id: string, season?: number, episode?: number): Promise<TitleInfo | null> {
  const m = (await json(`https://v3-cinemeta.strem.io/meta/${type}/${encodeURIComponent(id)}.json`))?.meta;
  if (!m?.name) return null;
  const info: TitleInfo = {
    name: m.name,
    year: (m.releaseInfo || m.year || "").toString().slice(0, 9) || undefined,
    overview: m.description || undefined,
    // Its posters come small by default: the medium one reads better in a chat's preview.
    poster: m.poster ? String(m.poster).replace("/poster/small/", "/poster/medium/") : undefined,
    background: m.background || undefined,
  };
  if (season != null && episode != null) {
    const v = (m.videos as Array<{ season?: number; episode?: number; name?: string; title?: string; thumbnail?: string }> | undefined)
      ?.find((x) => x.season === season && x.episode === episode);
    info.episode = { season, episode, name: v?.name || v?.title || undefined, still: v?.thumbnail || undefined };
  }
  return info;
}

async function tmdb(type: string, id: string, locale: string, season?: number, episode?: number): Promise<TitleInfo | null> {
  const key = process.env.TMDB_API_KEY;
  if (!key || !/^\d+$/.test(id)) return null;
  const kind = type === "movie" ? "movie" : "tv";
  const lang = `language=${encodeURIComponent(locale)}`;
  const m = await json(`https://api.themoviedb.org/3/${kind}/${id}?api_key=${encodeURIComponent(key)}&${lang}`);
  if (!m) return null;
  const img = (path?: string | null, size = "w500") => (path ? `https://image.tmdb.org/t/p/${size}${path}` : undefined);
  const info: TitleInfo = {
    name: m.title || m.name,
    year: (m.release_date || m.first_air_date || "").slice(0, 4) || undefined,
    overview: m.overview || undefined,
    poster: img(m.poster_path),
    background: img(m.backdrop_path, "w1280"),
  };
  if (kind === "tv" && season != null && episode != null) {
    const ep = await json(`https://api.themoviedb.org/3/tv/${id}/season/${season}/episode/${episode}?api_key=${encodeURIComponent(key)}&${lang}`);
    info.episode = { season, episode, name: ep?.name || undefined, still: img(ep?.still_path, "w780") };
  }
  return info.name ? info : null;
}

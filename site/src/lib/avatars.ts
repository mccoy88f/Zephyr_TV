/**
 * The Zephyr TV users' avatars, as the app keeps them (its Avatars.kt): "style/seed", drawn by DiceBear. The same
 * styles (free to use: CC0, or free for personal and commercial use) and the same seeds (random letters and digits,
 * nothing of the user's).
 */
export const STYLES = ["lorelei", "notionists", "thumbs", "bottts", "pixel-art", "avataaars"] as const;

export const STYLE_NAMES: Record<(typeof STYLES)[number], string> = {
  lorelei: "Lorelei",
  notionists: "Notionists",
  thumbs: "Thumbs",
  bottts: "Bottts",
  "pixel-art": "Pixel Art",
  avataaars: "Avataaars",
};

const SEED = /^[A-Za-z0-9]{1,24}$/;

/** [avatar]'s style and seed, or null when it isn't one. */
export function parseAvatar(avatar: string | null | undefined): { style: string; seed: string } | null {
  if (!avatar) return null;
  const [style, seed] = avatar.split("/");
  return (STYLES as readonly string[]).includes(style) && seed && SEED.test(seed) ? { style, seed } : null;
}

/** A new random seed: letters and digits. */
export function newSeed() {
  const chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(10));
  return Array.from(bytes, (b) => chars[b % chars.length]).join("");
}

/** The avatar's picture: SVG for the site (the app takes PNG), round, on one of a few soft colours. */
export function avatarUrl(avatar: string | null | undefined): string | null {
  const a = parseAvatar(avatar);
  return a ? `https://api.dicebear.com/9.x/${a.style}/svg?seed=${a.seed}&radius=50&backgroundColor=b6e3f4,c0aede,d1d4f9,ffd5dc,ffdfbf` : null;
}

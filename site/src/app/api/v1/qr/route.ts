import QRCode from "qrcode";
import { allow } from "@/server/limit";

export const dynamic = "force-dynamic";

/**
 * A QR code of [d] (up to 300 characters), as SVG: for Zephyr TV Receiver (webos/), which shows the link page with its
 * code to scan, and has no QR code maker of its own. Black on white, with its quiet zone.
 */
export async function GET(req: Request) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "?";
  if (!allow(`qr:${ip}`, 30, 60_000)) return new Response(null, { status: 429 });
  const d = new URL(req.url).searchParams.get("d") ?? "";
  if (!d || d.length > 300) return new Response(null, { status: 400 });
  const svg = await QRCode.toString(d, { type: "svg", margin: 2, errorCorrectionLevel: "M", color: { dark: "#000000", light: "#ffffff" } });
  return new Response(svg, { headers: { "Content-Type": "image/svg+xml", "Cache-Control": "public, max-age=600" } });
}

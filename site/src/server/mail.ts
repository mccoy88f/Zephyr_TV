import "server-only";
import nodemailer, { type Transporter } from "nodemailer";

let transporter: Transporter | null = null;

/** Whether the site can send email: SMTP_USER and SMTP_PASSWORD set. */
export const mailReady = () => Boolean(process.env.SMTP_USER && process.env.SMTP_PASSWORD);

/**
 * The SMTP server: OVH's shared one by default (ssl0.ovh.net, SSL on 465), signed in as SMTP_USER (the sender's
 * mailbox) with SMTP_PASSWORD.
 */
function smtp(): Transporter {
  if (transporter) return transporter;
  const port = Number(process.env.SMTP_PORT || 465);
  transporter = nodemailer.createTransport({
    host: process.env.SMTP_HOST || "ssl0.ovh.net",
    port,
    secure: port === 465, // 465: TLS from the start; any other port: STARTTLS, required (never in clear)
    requireTLS: port !== 465,
    connectionTimeout: 15_000, // a port blocked on the way out: an answer in seconds, not minutes
    greetingTimeout: 10_000,
    socketTimeout: 30_000,
    auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASSWORD },
  });
  return transporter;
}

/** [to] for the log: "an…@example.com", the address not written out whole. */
const masked = (to: string) => to.replace(/^(.{2})[^@]*/, "$1…");

/**
 * [text] (and [html]) to [to], from MAIL_FROM ("Zephyr TV <SMTP_USER>" by default): the SMTP server's answer
 * ("250 … queued as …"), also in the log either way. Throws when it can't be sent.
 */
export async function sendMail(to: string, subject: string, text: string, html: string): Promise<string> {
  if (!mailReady()) {
    console.warn(`[mail] not sent to ${masked(to)}: SMTP_USER and SMTP_PASSWORD are not set`);
    throw new Error("SMTP_USER and SMTP_PASSWORD are not set");
  }
  try {
    const info = await smtp().sendMail({ from: process.env.MAIL_FROM || `Zephyr TV <${process.env.SMTP_USER}>`, to, subject, text, html });
    const answer = `${info.response ?? ""}`.slice(0, 200);
    console.info(`[mail] "${subject}" to ${masked(to)}: accepted ${info.accepted?.length ?? 0}, refused ${info.rejected?.length ?? 0} · ${answer}`);
    if (info.rejected?.length) throw new Error(`refused: ${answer}`);
    return answer;
  } catch (e) {
    console.warn(`[mail] "${subject}" to ${masked(to)} failed:`, (e as Error).message);
    throw e;
  }
}

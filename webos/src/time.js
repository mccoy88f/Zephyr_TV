/** Clock and night-mode window. Minutes are counted from midnight. */

export function minutesNow(date) {
  return date.getHours() * 60 + date.getMinutes();
}

export function inNight(date, from, to, enabled) {
  if (!enabled) return false;
  const m = minutesNow(date);
  const a = Number(from) || 0;
  const b = Number(to) || 0;
  if (a === b) return true;
  if (a < b) return m >= a && m < b;
  return m >= a || m < b;
}

export function formatClock(date, mode) {
  const h24 = mode === "24" || (mode !== "12" && hourCycle(date) === 24);
  let h = date.getHours();
  const min = String(date.getMinutes()).padStart(2, "0");
  if (!h24) {
    const suffix = h >= 12 ? " PM" : " AM";
    h = h % 12 || 12;
    return h + ":" + min + suffix;
  }
  return String(h).padStart(2, "0") + ":" + min;
}

function hourCycle(date) {
  try {
    const hour = new Intl.DateTimeFormat(undefined, { hour: "numeric" }).formatToParts(date)
      .find((p) => p.type === "hour");
    if (hour && Number(hour.value) > 12) return 24;
    const sample = new Intl.DateTimeFormat(undefined, { hour: "numeric" }).resolvedOptions();
    if (sample.hourCycle === "h23" || sample.hourCycle === "h24") return 24;
  } catch (e) { /* Chromium 79 has Intl. A failure keeps the 24-hour clock. */ }
  return 24;
}

export function hhmm(minutes) {
  const m = ((Number(minutes) % 1440) + 1440) % 1440;
  return String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0");
}

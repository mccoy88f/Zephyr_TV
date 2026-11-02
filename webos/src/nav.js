/** Arrow keys move between [data-row] controls. Back is key 461 on webOS. */

export function isBack(event) {
  return event.keyCode === 461 || event.key === "Escape" || event.key === "GoBack" || event.keyCode === 10009;
}

export function isEnter(event) {
  return event.keyCode === 13 || event.key === "Enter";
}

function visible(el) {
  return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
}

function rowsOf(root) {
  const all = [...root.querySelectorAll("[data-row]")].filter(visible);
  const order = [];
  all.forEach((el) => {
    if (order.indexOf(el.dataset.row) === -1) order.push(el.dataset.row);
  });
  return { all, order };
}

export function move(root, keyCode) {
  const { all, order } = rowsOf(root);
  if (!all.length) return;
  const cur = document.activeElement;
  const has = all.indexOf(cur) !== -1;
  if (!has) {
    all[0].focus();
    return;
  }
  const row = cur.dataset.row;
  const inRow = all.filter((el) => el.dataset.row === row);
  const i = inRow.indexOf(cur);
  if (keyCode === 37 && i > 0) return focus(inRow[i - 1]);
  if (keyCode === 39 && i < inRow.length - 1) return focus(inRow[i + 1]);
  if (keyCode !== 38 && keyCode !== 40) return;
  const ri = order.indexOf(row);
  const next = order[ri + (keyCode === 40 ? 1 : -1)];
  if (!next) return;
  const x = cur.getBoundingClientRect().left;
  const targets = all.filter((el) => el.dataset.row === next);
  targets.sort((a, b) => Math.abs(a.getBoundingClientRect().left - x) - Math.abs(b.getBoundingClientRect().left - x));
  if (targets[0]) focus(targets[0]);
}

function focus(el) {
  el.focus();
  if (el.scrollIntoView) el.scrollIntoView({ block: "nearest", inline: "nearest" });
}

export function focusId(root, id) {
  const el = id && root.querySelector('[data-fid="' + cssAttr(id) + '"]');
  focus(el || root.querySelector("[data-row]"));
}

function cssAttr(value) {
  if (window.CSS && CSS.escape) return CSS.escape(value);
  return String(value).replace(/["\\]/g, "\\$&");
}

export function installKeys(root, handlers) {
  document.addEventListener("keydown", (event) => {
    const typing = document.activeElement && document.activeElement.tagName === "INPUT";
    if (handlers.playerKey && handlers.playerKey(event)) {
      event.preventDefault();
      return;
    }
    if (isBack(event)) {
      if (typing && event.key === "Escape") {
        event.preventDefault();
        handlers.back();
      } else if (!typing || event.keyCode === 461 || event.keyCode === 10009) {
        event.preventDefault();
        handlers.back();
      }
      return;
    }
    if (typing && event.keyCode !== 38 && event.keyCode !== 40 && !isEnter(event)) return;
    if (event.keyCode === 38 || event.keyCode === 40 || event.keyCode === 37 || event.keyCode === 39) {
      if (typing && (event.keyCode === 37 || event.keyCode === 39)) return;
      event.preventDefault();
      move(root, event.keyCode);
      return;
    }
    if (isEnter(event)) {
      const el = document.activeElement;
      if (el && (el.id === "q" || el.id === "editor")) {
        event.preventDefault();
        if (handlers.submit) handlers.submit();
        return;
      }
      if (!el || !el.dataset || !el.dataset.act) return;
      event.preventDefault();
      handlers.activate(el);
    }
  });
  root.addEventListener("click", (event) => {
    const el = event.target.closest && event.target.closest("[data-act]");
    if (!el) return;
    el.focus();
    handlers.activate(el);
  });
}

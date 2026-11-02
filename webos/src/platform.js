/** webOS Luna calls. In a browser the same functions answer empty. */

export function onTV() {
  return typeof PalmSystem !== "undefined" || typeof PalmServiceBridge !== "undefined";
}

export function platformBack() {
  if (window.PalmSystem && typeof PalmSystem.platformBack === "function") PalmSystem.platformBack();
}

export function luna(uri, method, parameters) {
  return new Promise((resolve, reject) => {
    if (typeof PalmServiceBridge === "undefined") {
      reject(new Error("no luna"));
      return;
    }
    const bridge = new PalmServiceBridge();
    bridge.onservicecallback = (msg) => {
      let json = {};
      try { json = JSON.parse(msg); } catch (e) { reject(e); return; }
      if (json.errorCode) reject(json);
      else resolve(json);
    };
    bridge.call(uri.replace(/\/$/, "") + "/" + method, JSON.stringify(parameters || {}));
  });
}

export async function sdkVersion() {
  try {
    const info = await luna("luna://com.webos.service.tv.systemproperty", "getSystemInfo", { keys: ["sdkVersion", "modelName"] });
    return info.sdkVersion || info.modelName || "";
  } catch (e) {
    return onTV() ? "" : "browser";
  }
}

export async function listApps() {
  try {
    const res = await luna("luna://com.webos.applicationManager", "listApps", {});
    const apps = res.apps || [];
    return apps.filter((a) => a && a.id && a.title && a.id !== "link.mccoy88f.zephyr" && a.visible !== false)
      .map((a) => ({ id: a.id, title: a.title, icon: a.icon || a.mediumIcon || "" }));
  } catch (e) {
    return [];
  }
}

export function launchApp(id) {
  return luna("luna://com.webos.applicationManager", "launch", { id });
}

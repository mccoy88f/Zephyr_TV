/** APIs Chromium 38 does not have. Syntax is lowered by the build; these stay as calls. */

if (typeof Object.assign !== "function") {
  Object.assign = function (target) {
    if (target == null) throw new TypeError("assign");
    var to = Object(target);
    for (var i = 1; i < arguments.length; i++) {
      var src = arguments[i];
      if (src == null) continue;
      for (var key in src) {
        if (Object.prototype.hasOwnProperty.call(src, key)) to[key] = src[key];
      }
    }
    return to;
  };
}

if (!Array.prototype.find) {
  Array.prototype.find = function (fn, self) {
    for (var i = 0; i < this.length; i++) {
      if (i in this && fn.call(self, this[i], i, this)) return this[i];
    }
  };
}

if (!Array.prototype.findIndex) {
  Array.prototype.findIndex = function (fn, self) {
    for (var i = 0; i < this.length; i++) {
      if (i in this && fn.call(self, this[i], i, this)) return i;
    }
    return -1;
  };
}

if (!String.prototype.startsWith) {
  String.prototype.startsWith = function (needle, at) {
    var s = String(this);
    var n = String(needle);
    var i = at > 0 ? at : 0;
    return s.slice(i, i + n.length) === n;
  };
}

if (!String.prototype.endsWith) {
  String.prototype.endsWith = function (needle, len) {
    var s = String(this);
    var n = String(needle);
    var end = len < s.length ? len : s.length;
    return s.slice(end - n.length, end) === n;
  };
}

if (!String.prototype.padStart) {
  String.prototype.padStart = function (len, pad) {
    var s = String(this);
    var fill = pad == null ? " " : String(pad);
    if (!fill) return s;
    while (s.length < len) s = fill + s;
    return s.length > len ? s.slice(s.length - len) : s;
  };
}

if (window.NodeList && !NodeList.prototype.forEach) {
  NodeList.prototype.forEach = Array.prototype.forEach;
}

if (window.Element && !Element.prototype.closest) {
  Element.prototype.closest = function (sel) {
    var match = Element.prototype.matches || Element.prototype.webkitMatchesSelector;
    if (!match) return null;
    var el = this;
    while (el && el.nodeType === 1) {
      if (match.call(el, sel)) return el;
      el = el.parentElement;
    }
    return null;
  };
}

if (typeof URLSearchParams !== "function") {
  function URLSearchParamsPolyfill() {
    this._pairs = [];
  }
  URLSearchParamsPolyfill.prototype.set = function (key, value) {
    var name = String(key);
    var next = String(value);
    for (var i = 0; i < this._pairs.length; i++) {
      if (this._pairs[i][0] === name) {
        this._pairs[i][1] = next;
        return;
      }
    }
    this._pairs.push([name, next]);
  };
  URLSearchParamsPolyfill.prototype.toString = function () {
    var out = [];
    for (var i = 0; i < this._pairs.length; i++) {
      out.push(encodeURIComponent(this._pairs[i][0]) + "=" + encodeURIComponent(this._pairs[i][1]));
    }
    return out.join("&");
  };
  window.URLSearchParams = URLSearchParamsPolyfill;
}

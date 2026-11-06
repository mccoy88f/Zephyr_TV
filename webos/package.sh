#!/bin/sh
# Zephyr TV Receiver for webOS: the web app (shared with VIDAA, in site/public/receiver: the site serves it to VIDAA
# TVs as a page) with webOS's own file (webos/app/appinfo.json), and its service (webos/service), packaged by LG's
# ares-package into [out] (default: webos-out). From the shared files, as there's one of each: its icons (80 and
# 130 px, from the 400 px one, with ImageMagick) and its version (platform.js's VERSION). Run from the repository's root.
set -e
out="${1:-webos-out}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
version="$(sed -n 's/.*var VERSION = "\([0-9.]*\)".*/\1/p' site/public/receiver/platform.js)"
[ -n "$version" ] || { echo "no VERSION in site/public/receiver/platform.js" >&2; exit 1; }
mkdir -p "$work/app" "$work/service"
cp site/public/receiver/* "$work/app/"
convert site/public/receiver/icon.png -resize 80x80 "$work/app/icon.png"
convert site/public/receiver/icon.png -resize 130x130 "$work/app/largeIcon.png"
sed "s/\"version\": \"[0-9.]*\"/\"version\": \"$version\"/" webos/app/appinfo.json > "$work/app/appinfo.json"
cp webos/service/* "$work/service/"
sed "s/\"version\": \"[0-9.]*\"/\"version\": \"$version\"/" webos/service/package.json > "$work/service/package.json"
ares-package "$work/app" "$work/service" -o "$out"

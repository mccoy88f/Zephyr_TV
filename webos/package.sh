#!/bin/sh
# Zephyr TV Receiver for webOS: the web app (shared with VIDAA, in site/public/receiver: the site serves it to VIDAA
# TVs as a page) with webOS's own files (webos/app: appinfo.json, its icons), and its service (webos/service),
# packaged by LG's ares-package into [out] (default: webos-out). Run from the repository's root.
set -e
out="${1:-webos-out}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/app"
cp site/public/receiver/* "$work/app/"
cp webos/app/* "$work/app/"
ares-package "$work/app" webos/service -o "$out"

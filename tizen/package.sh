#!/bin/sh
# Zephyr TV Receiver as a Tizen .wgt: the shared web app (site/public/receiver) with tizen/config.xml (its version
# platform.js's VERSION), signed with your certificate profile [profile] (Tizen Studio's Certificate Manager: a
# Samsung certificate with your TV's DUID) by Tizen's CLI, into [out] (default: tizen-out). Run from the repository's
# root. TizenBrew needs none of this: it takes the module from GitHub (see docs/receiver.md).
set -e
profile="${1:?usage: tizen/package.sh <certificate profile> [out]}"
out="${2:-tizen-out}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
version="$(sed -n 's/.*var VERSION = "\([0-9.]*\)".*/\1/p' site/public/receiver/platform.js)"
cp site/public/receiver/* "$work/"
sed "s/version=\"[0-9.]*\" viewmodes/version=\"$version\" viewmodes/" tizen/config.xml > "$work/config.xml"
tizen package -t wgt -s "$profile" -- "$work"
mkdir -p "$out"
mv "$work"/*.wgt "$out/"

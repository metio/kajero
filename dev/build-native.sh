#!/bin/sh
# SPDX-FileCopyrightText: The kajero Authors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Build the single-binary kajero: the AOT uberjar, then GraalVM native-image.
# Run with the toolchain .breto.edn names: breto run sh dev/build-native.sh
# (or bb build-native after eval "$(breto env)").
#
# The binary links only glibc dynamically, so it runs on any glibc distro
# without a JVM. The build needs a static zlib (libz.a, in zlib1g-dev on
# Debian and Ubuntu, zlib-ng-compat-static on Fedora), which GraalVM does
# not ship. On a host that cannot install it, put a libz.a in a directory
# and pass NATIVE_IMAGE_OPTS=--native-compiler-options=-L<that directory>;
# native-image does not hand LIBRARY_PATH on to the linker.
# sqlite-jdbc's own native-image feature embeds its SQLite library.
# commonmark is initialised at build time because kajero.web
# holds its parser and renderer in vars, which the image heap stores.
#
# A native executable takes -X, -XX: and -D arguments for itself wherever
# they stand, which would let a title or selector starting with them change
# the runtime (-Dorg.sqlite.lib.path names the SQLite library to load)
# instead of reaching kajero. The binary therefore parses no runtime
# options. That also leaves out GraalVM's exit handlers, so a SIGTERM would
# end it without running a shutdown hook; kajero.main installs its own
# handlers for serve and mcp, and the smoke test checks that the hooks ran.
# The heap is capped at build time, at the -Xmx128m the service runs the
# JVM with; the uncapped serial heap held 125-186 MB RSS on a copy of the
# live notebook against 85-95 MB with the cap.
#
# NATIVE_IMAGE_OPTS adds options, such as -Ob for a quicker build while
# developing, or --gc=G1.
set -eu
cd "$(dirname "$0")/.."

echo "==> uberjar (tools.build)"
clojure -T:build uber

echo "==> native-image"
# shellcheck disable=SC2086 # NATIVE_IMAGE_OPTS is split into options on purpose
native-image \
  -jar target/kajero.jar \
  -o target/kajero \
  --static-nolibc \
  --no-fallback \
  --enable-native-access=ALL-UNNAMED \
  -march=compatibility \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --initialize-at-build-time=com.fasterxml.jackson \
  --initialize-at-build-time=org.commonmark \
  -H:+UnlockExperimentalVMOptions -H:-ParseRuntimeOptions -H:-UnlockExperimentalVMOptions \
  -R:MaxHeapSize=128m \
  -J-Xmx6g \
  ${NATIVE_IMAGE_OPTS:-}

echo "==> built:"
ls -lh target/kajero

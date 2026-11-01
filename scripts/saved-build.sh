#!/bin/bash
# The build saved between sessions, on the orphan branch `saved-build` of this
# repository (one commit, replaced with a force-push each time, so binaries do
# not pile up in the history of main; oorexxwasm's scheme).
#
#   saved-build.sh manifest          print what the build depends on
#   saved-build.sh save    [WORK]    pack WORK/dist and the build tree into WORK/saved
#   saved-build.sh publish [WORK]    push WORK/saved to the branch saved-build
#   saved-build.sh fetch   [WORK]    restore the saved build into WORK if its
#                                    MANIFEST matches this checkout; exit 1 if not
#
# Two packs: oorexxjava-dist.tar.gz (dist/: rexxj and its jar, rexx.wasm,
# rexx.img, the .cls files, the samples; and dist-test/, build-test.sh's
# variant with the test suite's native libraries) is enough to run programs and
# the test suite; oorexxjava-tree.tar.gz (the patched ooRexx source and the CMake build
# tree) makes ./build.sh WORK incremental, so that a change in C recompiles only
# what it touches.  The build tree holds absolute paths: it only works at the
# WORK it was saved from (MANIFEST says which), /home/claude/work by default.
# A rebuild still downloads wasi-sdk (not saved: 635 MB).
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
CMD=${1:?usage: saved-build.sh manifest|save|publish|fetch [WORK]}
WORK=${2:-/home/claude/work}
BRANCH=saved-build
# what the build is made from: every tracked file it reads, by content
INPUTS="build.sh build-test.sh 0001-wasi-on-top-of-oorexxwasm-0001.patch wasi-p1-threads.cmake compat host"

manifest() {
  echo "work $WORK"
  (cd "$HERE" && git ls-files -- $INPUTS | LC_ALL=C sort | xargs sha256sum)
}

case $CMD in
manifest)
  manifest ;;
save)
  [ -x "$WORK/dist/rexxj" ] || { echo "no build in $WORK/dist" >&2; exit 1; }
  rm -rf "$WORK/saved" && mkdir -p "$WORK/saved"
  manifest > "$WORK/saved/MANIFEST"
  [ -x "$WORK/dist-test/rexxj" ] || { echo "no test variant: ./build-test.sh $WORK" >&2; exit 1; }
  tar -C "$WORK" -czf "$WORK/saved/oorexxjava-dist.tar.gz" dist dist-test
  tar -C "$WORK" -czf "$WORK/saved/oorexxjava-tree.tar.gz" oorexx build toolchain.cmake
  ls -l "$WORK/saved" ;;
publish)
  diff -q <(manifest) "$WORK/saved/MANIFEST" >/dev/null \
    || { echo "$WORK/saved/MANIFEST does not match this checkout: save again" >&2; exit 1; }
  g() { git -C "$HERE" "$@"; }
  readme=$(printf '%s\n' "Saved build of ooRexx for the JVM (binaries, not sources)." \
    "scripts/saved-build.sh fetch restores it when MANIFEST matches the checkout." | g hash-object -w --stdin)
  tree=$(printf '100644 blob %s\t%s\n' \
    "$(g hash-object -w "$WORK/saved/MANIFEST")" MANIFEST \
    "$readme" README.md \
    "$(g hash-object -w "$WORK/saved/oorexxjava-dist.tar.gz")" oorexxjava-dist.tar.gz \
    "$(g hash-object -w "$WORK/saved/oorexxjava-tree.tar.gz")" oorexxjava-tree.tar.gz | g mktree)
  commit=$(g commit-tree "$tree" -m "Saved build (main at $(g rev-parse --short HEAD))")
  g push -q -f origin "$commit:refs/heads/$BRANCH"
  echo "published on $BRANCH: $commit" ;;
fetch)
  T=$(mktemp -d)
  git -C "$HERE" fetch -q --depth 1 origin "$BRANCH" || { echo "no branch $BRANCH" >&2; exit 1; }
  git -C "$HERE" archive FETCH_HEAD | tar -x -C "$T"
  if ! diff <(manifest) "$T/MANIFEST"; then
    echo "the saved build is for other sources (diff above): run ./build.sh $WORK" >&2
    rm -rf "$T"; exit 1
  fi
  mkdir -p "$WORK"
  rm -rf "$WORK/dist" "$WORK/dist-test" "$WORK/oorexx" "$WORK/build"
  tar -C "$WORK" -xzf "$T/oorexxjava-dist.tar.gz"
  tar -C "$WORK" -xzf "$T/oorexxjava-tree.tar.gz"
  # the clone's files are newer than the saved objects (checkout time), and
  # every object depends on compat/orx_wasi_compat.h: with the same contents
  # (MANIFEST), date them back so that make does not rebuild everything
  (cd "$HERE" && git ls-files -- $INPUTS | xargs touch -d '2000-01-01')
  rm -rf "$T"
  echo "restored into $WORK: $WORK/dist/rexxj program.rex" ;;
*)
  echo "usage: saved-build.sh manifest|save|publish|fetch [WORK]" >&2; exit 2 ;;
esac

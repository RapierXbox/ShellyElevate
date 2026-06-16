#!/bin/sh
# creates and pushes a vX.Y.Z tag which triggers the release-apk workflow
# usage: release.sh [version]   e.g. release.sh v3.26111.1430
#        with no arg a tag is generated as 3.YYDDD.HHMM matching the gradle scheme
set -e

REMOTE="origin"

VERSION="$1"
if [ -z "$VERSION" ]; then
    YEAR=$(date +%y)
    DOY=$(date +%j)
    HHMM=$(date +%H%M)
    VERSION="3.${YEAR}${DOY}.${HHMM}"
fi

# normalise to a leading v
case "$VERSION" in
    v*) TAG="$VERSION" ;;
    *)  TAG="v$VERSION" ;;
esac

# bail on uncommitted tracked changes so the released apk matches what is committed
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "error: working tree is dirty, commit or stash first"
    exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "error: tag $TAG already exists"
    exit 1
fi

echo "tagging $TAG and pushing to $REMOTE"
git tag -a "$TAG" -m "$TAG"
git push "$REMOTE" "$TAG"

echo "done. watch the Build and Release APK workflow on github"

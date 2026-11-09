#!/usr/bin/env bash
# Publish the SDK to Maven Central through the Sonatype Central Portal.
#
#   scripts/publish-central.sh bundle            build, sign, verify and zip (no network)
#   scripts/publish-central.sh upload            bundle, then upload it for validation (not released)
#   scripts/publish-central.sh release <id>      release a validated deployment (IRREVERSIBLE)
#   scripts/publish-central.sh status <id>       show a deployment's state
#
# Needs (nothing is stored in this repo):
#   - GPG_KEY_ID                      the signing key, e.g. export GPG_KEY_ID=ABCD1234... (bundle/upload)
#   - the Central Portal user token   macOS keychain item "identified-central-portal" holding
#                                     "<token username>:<token password>", or env CENTRAL_PORTAL_TOKEN
# Signing goes through gpg-agent, so the key passphrase is typed into pinentry only.
set -euo pipefail

cd "$(dirname "$0")/.."
api="https://central.sonatype.com/api/v1/publisher"
stage="identified-sdk/build/release-repository"
out="build/central"

token() {
  if [ -n "${CENTRAL_PORTAL_TOKEN:-}" ]; then printf %s "$CENTRAL_PORTAL_TOKEN" | base64; return; fi
  security find-generic-password -s identified-central-portal -w 2>/dev/null | tr -d '\n' | base64 ||
    { echo "no token: set CENTRAL_PORTAL_TOKEN or add keychain item identified-central-portal" >&2; exit 1; }
}

version() { sed -n 's/^version = "\(.*\)"/\1/p' identified-sdk/build.gradle.kts; }
group_path() { sed -n 's/^group = "\(.*\)"/\1/p' identified-sdk/build.gradle.kts | tr . /; }

bundle() {
  : "${GPG_KEY_ID:?export GPG_KEY_ID=<signing key id>}"
  local v; v="$(version)"
  rm -rf "$stage" "$out"; mkdir -p "$out"
  ./gradlew --no-daemon :identified-sdk:publishReleasePublicationToReleaseStagingRepository \
    -Psigning.gnupg.keyName="$GPG_KEY_ID" ${GPG_HOME:+-Psigning.gnupg.homeDir="$GPG_HOME"}
  # Central rejects bundles with a missing file, a missing signature or an incomplete POM.
  local dir="$stage/$(group_path)/identified-sdk/$v" f
  for f in "identified-sdk-$v.aar" "identified-sdk-$v.pom" "identified-sdk-$v.module" \
           "identified-sdk-$v-sources.jar" "identified-sdk-$v-javadoc.jar"; do
    [ -f "$dir/$f" ] && [ -f "$dir/$f.asc" ] || { echo "missing $f or its .asc" >&2; exit 1; }
  done
  for tag in '<name>' '<description>' '<url>' '<licenses>' '<developers>' '<scm>'; do
    grep -q "$tag" "$dir/identified-sdk-$v.pom" || { echo "POM lacks $tag" >&2; exit 1; }
  done
  # The portal computes metadata itself; the staging repo's maven-metadata* files are not part of a bundle.
  (cd "$stage" && find . -type f ! -name 'maven-metadata*' | sed 's|^\./||' | zip -q -@ "$OLDPWD/$out/identified-sdk-$v-bundle.zip")
  echo "bundle: $out/identified-sdk-$v-bundle.zip"
}

case "${1:-}" in
  bundle) bundle ;;
  upload)
    bundle
    id=$(curl -fsS -X POST -H "Authorization: Bearer $(token)" \
      -F "bundle=@$out/identified-sdk-$(version)-bundle.zip" \
      "$api/upload?name=identified-sdk-$(version)&publishingType=USER_MANAGED")
    echo "deployment id: $id"
    echo "check:   scripts/publish-central.sh status $id   (wait for VALIDATED)"
    echo "release: scripts/publish-central.sh release $id" ;;
  status) curl -fsS -X POST -H "Authorization: Bearer $(token)" "$api/status?id=${2:?deployment id}"; echo ;;
  release) curl -fsS -X POST -H "Authorization: Bearer $(token)" "$api/deployment/${2:?deployment id}" && echo "release requested" ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac

# Releasing to Maven Central

Coordinates: `io.github.fening:identified-sdk:<version>`. Central releases are immutable: a
published version can never be changed or deleted, so a bad build means a new version.

## One-time setup (owner)

Shared with the Zuno SDK, which is already on Central under the same namespace and key:

1. Namespace `io.github.fening` is verified at <https://central.sonatype.com> (Namespaces). Nothing to add.
2. **Account > Generate User Token**. Store `<username>:<password>` in the macOS keychain, typed
   at the prompt (never on a command line):
   `security add-generic-password -s identified-central-portal -a "$USER" -w`
3. Signing key: the existing "Zuno SDK Releases" key (fingerprint
   `9C4468692B0A9261E43B74E62BC529B7F9329230`, expires 2028-11-02) is already on keyserver.ubuntu.com
   and keys.openpgp.org. `export GPG_KEY_ID=9C4468692B0A9261E43B74E62BC529B7F9329230`.

## Each release

1. Bump `version` in `identified-sdk/build.gradle.kts`, update the README coordinates, merge to
   `main`, and tag the merge commit with the same version (`git tag 0.1.1 && git push origin 0.1.1`).
   The tag also triggers JitPack on first request.
2. `export GPG_KEY_ID=<fingerprint>` and run `scripts/publish-central.sh upload`. It builds, signs
   (gpg-agent asks for the passphrase), checks every file has a `.asc`, zips and uploads for
   validation only. Note the deployment id it prints.
3. `scripts/publish-central.sh status <id>` until `VALIDATED` (or see it at
   <https://central.sonatype.com/publishing/deployments>). `FAILED` lists the rule that broke.
4. `scripts/publish-central.sh release <id>`, or click **Publish** in the portal. Irreversible.
5. Wait for sync (usually 10-30 minutes), then verify: the file
   `https://repo1.maven.org/maven2/io/github/fening/identified-sdk/<version>/identified-sdk-<version>.pom`
   returns 200, and a scratch Gradle project with only `mavenCentral()` resolves
   `io.github.fening:identified-sdk:<version>`.
6. Check JitPack still builds the tag: <https://jitpack.io/#fening/identified-android-sdk>.

## Dry run (no credentials, no network upload)

`./gradlew :identified-sdk:publishReleasePublicationToMavenLocal` installs unsigned artifacts into
`~/.m2`. Signing is opt-in (`-Psigning.gnupg.keyName=...`), so JitPack builds are unaffected.

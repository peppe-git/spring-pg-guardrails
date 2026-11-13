# Releasing

Releases go to Maven Central through the Central Portal. A pushed tag `v<version>` starts
`.github/workflows/release.yml`, which builds, tests, signs and uploads the modules `pg-rls-core`,
`spring-pg-guardrails` and `pg-rls-test` (plus the parent POM). `examples/rls-demo` is never published. The
deployment is uploaded with `autoPublish=false`: nothing is public until a maintainer publishes it in the portal.

## One-time setup

1. The namespace (the `groupId`) is verified in the [Central Portal](https://central.sonatype.com).
2. A Central Portal **user token** exists for the account that owns the namespace.
3. A GPG key for signing exists, and its public key is published on a public key server.
4. The repository has these Actions secrets:

   | Secret | Content |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | user token name from the Central Portal |
   | `MAVEN_CENTRAL_PASSWORD` | user token password from the Central Portal |
   | `GPG_PRIVATE_KEY` | ASCII-armored secret key: `gpg --armor --export-secret-keys <key id>` |
   | `GPG_PASSPHRASE` | passphrase of that key |

## Steps

1. Make sure `main` is green and up to date locally.
2. In `CHANGELOG.md`, move the entries under *Unreleased* to a new section `## [X.Y.Z] - <date>`.
3. Set the release version in every POM:

   ```bash
   ./mvnw -q org.codehaus.mojo:versions-maven-plugin:2.25.1:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   ```

4. Dry-run the release profile (no signature, no upload); it must end with `BUILD SUCCESS`:

   ```bash
   ./mvnw -Prelease -Dgpg.skip -DskipTests clean verify
   ```

   To inspect exactly what would be uploaded, add `-Dcentral.skipUpload=true` and run `deploy` instead of
   `verify`: the bundle is written to `target/central-publishing/central-bundle.zip`.

5. Commit and tag:

   ```bash
   git commit -am "chore(release): X.Y.Z"
   git tag -a vX.Y.Z -m "X.Y.Z"
   git push origin main vX.Y.Z
   ```

6. Watch the `release` workflow. It refuses to run when the POM version is a `-SNAPSHOT` or differs from the tag.
7. In the Central Portal, open *Deployments*, check the validated deployment (artifacts, signatures, POM metadata)
   and press **Publish**. Propagation to Maven Central can take a while.
8. Create the GitHub release for the tag, with the CHANGELOG section as notes.
9. Move to the next development version and push:

   ```bash
   ./mvnw -q org.codehaus.mojo:versions-maven-plugin:2.25.1:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "chore: start X.Y.(Z+1)-SNAPSHOT"
   git push origin main
   ```

   Add an empty `## [Unreleased]` section to `CHANGELOG.md` in the same commit.

## If something goes wrong

- The workflow failed before the upload: fix, delete the tag locally and remotely, tag again.
- A deployment was uploaded but is wrong: drop it in the Central Portal instead of publishing it, fix, and release
  a new version. A version published to Maven Central can never be changed or deleted.

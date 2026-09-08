# Releasing

Releases are published to Maven Central by `.github/workflows/release.yml` when a `v*` tag is
pushed. **The first publish is blocked until the one-time setup below is done** — the workflow
fails with a clear error until the four secrets exist.

## One-time setup (requires the account owner)

### 1. Central Portal account + the `sa.arsel` namespace

1. Create an account at [central.sonatype.com](https://central.sonatype.com) (top-right → Sign in →
   Sign up).
2. Verify the namespace: avatar → **View Namespaces** → **Add Namespace** → enter `sa.arsel`.
   The portal shows a **verification key** and asks you to prove control of `arsel.sa`: add a DNS
   **TXT record** on `arsel.sa` (host/name = `@`, value = the verification key exactly as shown),
   wait for DNS to propagate, then click **Verify Namespace**. The TXT record can be deleted once
   the namespace shows *Verified*.
3. Generate publishing credentials: avatar → **View Account** → **Generate User Token**. This
   yields a username/password pair — these token values (not your portal login) are the
   `mavenCentralUsername` / `mavenCentralPassword` secrets.

### 2. GPG signing key

```bash
gpg --full-generate-key            # RSA 4096 (or ed25519), no expiry is fine for signing
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # Central verifies against public keyservers
gpg --export-secret-keys --armor <KEY_ID>                   # the in-memory key secret, full block
```

The export prints the private key in plain text — paste the whole
`-----BEGIN PGP PRIVATE KEY BLOCK-----` … `-----END PGP PRIVATE KEY BLOCK-----` block into the
secret, newlines included.

### 3. GitHub repository secrets

Settings → Secrets and variables → Actions, exactly these names:

| Secret | Value |
| --- | --- |
| `ORG_GRADLE_PROJECT_mavenCentralUsername` | Central user-token username |
| `ORG_GRADLE_PROJECT_mavenCentralPassword` | Central user-token password |
| `ORG_GRADLE_PROJECT_signingInMemoryKey` | armored private key block |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | the key's passphrase (empty if none) |

Signing is wired only when `signingInMemoryKey` is present, so local builds and CI-without-secrets
skip it and stay green.

## Per release

These steps are **one commit, and that commit is the release** — never bump the version in a feature
commit. An untagged bump publishes nothing while burning the number: the API's per-layout SDK gate
then withholds layouts from a build that does not exist, and the feature stays dark in the field
even though the code is on `main`.

1. Bump `VERSION_NAME` in `gradle.properties` (single source of truth; no `-SNAPSHOT`).
2. Retitle the changes in `CHANGELOG.md` as `## [X.Y.Z] — YYYY-MM-DD` — the workflow extracts this
   section as the release notes and fails if it is missing.
3. Commit, then tag and push:

   ```bash
   git tag vX.Y.Z && git push origin main vX.Y.Z
   ```

The workflow runs the test suite, uploads both modules via `publishToMavenCentral`, and creates the
GitHub Release.

4. **Publish the deployment by hand.** The upload step deliberately stops after validation, so
   nothing reaches Central until you sign it off: go to
   [central.sonatype.com](https://central.sonatype.com) → **Deployments**, check the bundle
   (coordinates, version, that both `core` and `push-fcm` are present and signed), then click
   **Publish**. Availability follows 10–30 minutes later.

   This is deliberate: a Central artifact is permanent and can never be replaced, only superseded by
   a new version. Note the GitHub Release is created *before* this step, so between the two the
   release exists on GitHub but not yet on Central — clicking Publish closes that gap.

   To auto-release instead, swap the workflow's `publishToMavenCentral` back to
   `publishAndReleaseToMavenCentral` and drop this step.

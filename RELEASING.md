# Releasing haven-wear

Releases are a signed tag plus a GitHub release; the `android` workflow builds
the APK and uploads it. Order matters — follow it exactly.

## Steps

1. Land the change on `main` and wait for the `android` run to go fully green
   (`build`: vetkeys host tests, unit tests, debug APK).
2. Tag the commit — **signed, before anything else**:
   ```bash
   SHA=$(git rev-parse --short HEAD)
   git tag -s "v0.1.N-$SHA" -m "v0.1.N-$SHA — <one-line description>" HEAD
   git push origin "v0.1.N-$SHA"
   ```
   Tag scheme is `v0.1.<next>-<short-sha>` (same as haven-mobile). Bump the
   middle number by one from the latest release.
3. Create the release **immediately after pushing the tag** (the tag run's
   upload step needs the release to exist; use the full SHA — short SHAs are
   rejected as `target_commitish`):
   ```bash
   FULL=$(git rev-parse HEAD)
   gh release create "v0.1.N-$SHA" \
     --title "v0.1.N-$SHA — <one-line description>" \
     --notes "<what changed>" \
     --target "$FULL"
   ```
4. Watch the tag run and confirm the APK lands on the release:
   ```bash
   gh run watch <run-id> --exit-status
   gh release view "v0.1.N-$SHA" --json assets --jq '.assets[] | .name'
   ```

## Why this order

- `gh release create` mints its own **unsigned** tag if the name doesn't exist
  yet. Creating the release first therefore blocks the signed tag (`Updates
  were rejected because the tag already exists`). Signed tag first avoids it.
- The workflow uploads with `gh release upload`, which fails if the release
  doesn't exist — so the release must be created before the tag run's build
  job finishes. Creating it right after the push wins that race by minutes.

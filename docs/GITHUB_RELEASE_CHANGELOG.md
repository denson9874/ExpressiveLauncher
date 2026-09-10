# GitHub release changelog standard

Every newly published Expressive Launcher build gets an original, polished GitHub release
changelog. This applies to QA and to separately authorized stable releases; it does not authorize
stable promotion. The user requested catchy, funny notes with jokes and riddles that fit the
actual changes. Treat the changelog as part of completing a release.

## Editorial style

- Use a memorable title that retains the product name, version and channel, such as
  `Expressive Launcher 1.0.13 QA — Call Me by My Number`.
- Open with a short hook, then explain the user-visible change and why it helps. Compare the
  candidate with the previous published build; do not recycle old features as new changes.
- Keep the size proportional to the release. One focused fix deserves a few useful bullets,
  not an invented list of improvements. Use concrete before/after examples when helpful.
- Include one or two short, friendly jokes tied to those changes. Joke about the bug or the
  situation, not the user. Keep the important behavior, limitations and update instructions clear.
- Include one original release-themed riddle, with its answer behind a GitHub `<details>` reveal.
  The answer should make sense from the clues. Vary the theme and wording between releases.
- Briefly state validation that actually passed and relevant installation guidance. Distinguish
  emulator from physical-device checks and expedited jobs from normal scheduling. Never invent
  performance gains, test results, features or compatibility claims for a punchline.
- Keep source revision, version code, APK integrity information and upstream attribution in a
  compact technical section. Preserve the publisher's exact hidden release-identity comment.

The completed [1.0.13 notes](releases/1.0.13.md) demonstrate the style. Future notes should be
freshly written from that release's evidence, not produced by replacing its version number.

## Publication workflow

1. Prepare a Markdown changelog and title from the actual candidate diff, parity ledger and
   retained QA evidence. Keep the final authored file in the run's ignored artifact directory.
2. Let Jenkins own signing, packaging, assets and channel publication. Wait for its exact
   `provider=github`, `status=released`, `feedVerified=true` receipt. An editorial operation cannot
   bypass those gates or turn a partial publication into a completed release.
3. Read and retain the existing GitHub release and channel manifest. Match its tag, source, APK
   hash and version code to the verified receipt. Preserve its exact channel-specific identity
   comment (`<!-- expressive-qa ... -->` for QA) and upstream license attribution in the new body.
4. Update only the matching release's title and Markdown body, using the existing authenticated
   GitHub CLI and `--notes-file` (never interpolated multiline shell text). For example, for QA:

   ```sh
   gh release edit qa-vVERSION-CODE --repo denson9874/ExpressiveLauncher \
     --title 'Expressive Launcher VERSION QA — Release Theme' \
     --notes-file /absolute/path/to/run-artifacts/release-notes.md
   ```

5. Read the release back and require exact title/body agreement and the retained identity comment.
   Verify its release ID, tag, target, draft/prerelease status and asset IDs, sizes and hashes remain
   unchanged; the QA/stable manifests must also remain unchanged by this editorial operation.
   Save the before/after snapshots and result with the run's evidence. Review the rendered notes
   for readable structure and a working answer reveal when browser access is available.
6. Include the changelog's release link in the completion response. If the notes update fails,
   retain the draft and report the editorial failure separately from the APK publication result;
   retry the same release metadata without rebuilding, incrementing versions or replacing assets.

The current publisher creates a generic body only when creating a release. Retries validate the
identity comment and preserve an existing body, so authored notes survive delivery retries.
Do not rerun a build solely to edit release prose. Keep original signed seals and their reports
immutable; later editorial evidence belongs beside the run artifacts.

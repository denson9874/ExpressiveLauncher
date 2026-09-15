# Contributing to Expressive Launcher

Use [this repository's issues](https://github.com/denson9874/ExpressiveLauncher/issues) for Expressive bugs and feature requests. Include the Expressive version/channel, Android version, device model, reproduction steps, and expected behavior. Share only screenshots or logs you intend to make public.

Start with the [build guide](docs/BUILDING.md) and [source provenance](docs/SOURCE_PROVENANCE.md). Keep changes focused, preserve the Launcher3/Lawnchair architecture and existing source notices, and include meaningful regression coverage for functional changes. Explain how a pull request was validated, including device checks when behavior is visible on Home.

Official signed releases are produced by the maintainer's Jenkins pipeline. Contributor builds use their own development or signing configuration. Archived upstream GitHub workflows in [docs/reference/upstream-github](docs/reference/upstream-github/) are retained for context; they do not run from this repository.

## Preserve contribution credit

- Retain applicable source headers, component licenses, and the root [NOTICE](NOTICE).
  Preserve original authors when importing commits; identify the upstream repository,
  license, and exact source revision for copied or adapted material.
- Add a prominent modification notice when changing an inherited file, keeping
  existing notices. For a new Apache-licensed Expressive source file, include an
  accurate copyright notice, `SPDX-License-Identifier: Apache-2.0`, and the project URL.
  A contributor retains ownership of their own copyrightable contributions.
- Explain the origin of a contribution and credit coauthors accurately. Update the
  [source provenance](docs/SOURCE_PROVENANCE.md) or
  [third-party notices](docs/THIRD_PARTY_NOTICES.md) when introducing a new origin or component.
- Keep `lawnchair/assets/expressive-NOTICE.txt` identical to the root NOTICE. Run
  `python3 ci/check_attribution.py` before submitting. License-text changes require
  deliberate review of the relevant rights and the check's recorded hashes.

GitHub Actions checks attribution, runs app unit tests and developer/benchmark
builds, verifies merged launcher manifest security, and performs CodeQL analysis.
Official signing, device validation, and publication remain in Jenkins.
See [reuse and attribution](docs/REUSE_AND_ATTRIBUTION.md) for downstream guidance.

# Expressive Launcher 5.0.0 — Jenkins stable release

This report describes the exact APK built and tested by Jenkins. Publication status is recorded separately in the publication receipt.

This build uses an explicitly selected QA version and current committed source. The legacy validationOnly marker records that source selection; green stable publication requires a separate exact-build authorization and receipt.


- APK: `ExpressiveLauncherL3-5.0.0-Android17-QPR2-Beta5-Jenkins-Release-release-signed.apk`
- Version code: 62
- Package/channel: `dev.launcher.expressive.l3` / stable
- Source revision: `999c803100db313d93de35c6b7e839b894fd9346`
- Jenkins run: http://127.0.0.1:8091/job/expressive-release-build/3/
- Bytes: 24372758
- SHA-256: `639acdde5fea558d47790f01380f207caa9ec0809d557dd0392845eb5f27df49`
- Certificate SHA-256: `c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2`
- Unit tests: 550 passed, 0 failures, 0 errors, 0 skipped.
- Signature, non-debuggable packaging, version progression, and prior-certificate checks passed.
- Automated device result: passed. The attached qa-result.json and evidence describe the observed flows and guest identity.

The automated upgrade uses the documented quiesced ADB install sequence on an isolated emulator. It does not establish a live updater download or system-installer result; those are recorded separately after publication. The Android foreground replacement limitation remains documented in docs/DIRECT_DISTRIBUTION.md.

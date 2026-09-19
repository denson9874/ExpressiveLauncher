# Expressive Launcher 2.0.8 — Jenkins stable release

This report describes the exact APK built and tested by Jenkins. Publication status is recorded separately in the publication receipt.

This build uses an explicitly selected QA version and current committed source. The legacy validationOnly marker records that source selection; green stable publication requires a separate exact-build authorization and receipt.


- APK: `ExpressiveLauncherL3-2.0.8-Android17-QPR2-Beta5-Jenkins-Release-release-signed.apk`
- Version code: 26
- Package/channel: `dev.launcher.expressive.l3` / stable
- Source revision: `b56bd0e3c9b53a323f7daa489b2d71c812b34807`
- Jenkins run: http://127.0.0.1:8091/job/expressive-release-build/8/
- Bytes: 23943857
- SHA-256: `f0368d2af067fd64eec9e688b813999d80e17d6e2ed16979d444671b56bff703`
- Certificate SHA-256: `c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2`
- Unit tests: 237 passed, 0 failures, 0 errors, 0 skipped.
- Signature, non-debuggable packaging, version progression, and prior-certificate checks passed.
- Automated device result: passed. The attached qa-result.json and evidence describe the observed flows and guest identity.

The automated upgrade uses the documented quiesced ADB install sequence on an isolated emulator. It does not establish a live updater download or system-installer result; those are recorded separately after publication. The Android foreground replacement limitation remains documented in docs/DIRECT_DISTRIBUTION.md.

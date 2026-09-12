# Expressive Launcher 1.0.16 — Jenkins stable release

This report describes the exact APK built and tested by Jenkins. Publication status is recorded separately in the publication receipt.

This is a private pipeline-validation artifact and is not eligible for publication.


- APK: `ExpressiveLauncherL3-1.0.16-Android17-QPR2-Beta4-Jenkins-Release-release-signed.apk`
- Version code: 17
- Package/channel: `dev.launcher.expressive.l3` / stable
- Source revision: `e49bbfe3d60ac5823db6ab302964917790b756d8`
- Jenkins run: http://127.0.0.1:8091/job/expressive-release-build/7/
- Bytes: 21774868
- SHA-256: `4b4126642c3e6d485781ce5043a048d071278f9c58211d9d091811df0a5b9399`
- Certificate SHA-256: `c14160306d5c059b3d119f15fb74e08c57cc272316e80b36e192c71dc9e4d0d2`
- Unit tests: 197 passed, 0 failures, 0 errors, 0 skipped.
- Signature, non-debuggable packaging, and selected QA version/certificate checks passed. No prior stable release exists; no prior-version upgrade is claimed.
- Automated device result: passed. The attached qa-result.json and evidence describe the observed flows and guest identity.

The first stable installation was tested on an isolated emulator, including a same-version reinstall and preference retention. This is not an upgrade from a previously shipped stable version. It does not establish a live updater download or system-installer result; those are recorded separately after publication. The Android foreground replacement limitation remains documented in docs/DIRECT_DISTRIBUTION.md.

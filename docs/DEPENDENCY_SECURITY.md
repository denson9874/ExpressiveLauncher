# Dependency security maintenance

The shared [dependency constraints](../gradle/dependency-security.gradle) apply
before project plugins resolve, covering buildscript classpaths and project
configurations, including Android device-test tooling and benchmarks.

| Dependency family | Minimum version | Relevant fixes |
| --- | --- | --- |
| Netty modules | 4.1.137.Final | HTTP/TLS parsing, denial of service, and [SNI routing](https://github.com/advisories/GHSA-c4c3-7fpv-j4q5) |
| Bouncy Castle provider, PKIX, and utility | 1.85 | [GOST counter reuse](https://github.com/advisories/GHSA-574f-3g2m-x479), [provider](https://github.com/advisories/GHSA-c3fc-8qff-9hwx), and [PKIX](https://github.com/advisories/GHSA-wg6q-6289-32hp) fixes |
| Wire runtime and JVM runtime | 6.4.7 | [Negative group lengths](https://github.com/advisories/GHSA-7xpr-hc2w-34m9) and subsequent reader-limit fixes |
| Apache HttpClient | 4.5.14 | [Cross-site scripting](https://github.com/advisories/GHSA-7r82-7xv7-xcpj) |
| Apache Commons Lang | 3.18.0 | [Uncontrolled recursion](https://github.com/advisories/GHSA-j288-q9x7-2f5v) |
| JDOM | 2.0.6.1 | [External entity processing](https://github.com/advisories/GHSA-2363-cqg2-863c) |
| Plexus Utils | 3.6.1 | [File handling](https://github.com/advisories/GHSA-6fmv-xxpf-w3cw) |
| jose4j | 0.9.6 | [Compressed JWE denial of service](https://github.com/advisories/GHSA-3677-xxcr-wjqv) |

These constraints upgrade existing dependencies and allow newer versions; they
do not introduce these libraries into configurations that do not use them.
Wire's multiplatform metadata selects the JVM runtime, so both coordinates are
covered. Bouncy Castle's minimum matches the existing Robolectric provider.

## Kotlin alert applicability

Dependabot alert #49 ([GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp))
is attributed to Kotlin Gradle Plugin 2.4.10. The
[upstream security fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6)
changes KAPT's `JavaClassCacheManager` deserialization. This build uses KSP and
ordinary Java annotation processors; it does not apply KAPT. The complete
dependency graph contains no `kotlin-annotation-processing` or KAPT runtime,
and the selected Kotlin Gradle plugin/compiler jars do not contain that class.
The reported vulnerable component is therefore not used by this source build.

Kotlin remains at 2.4.10 to preserve the supported CodeQL analysis path.
GitHub [confirmed](https://github.com/github/codeql/issues/22381)
that Kotlin 2.4.20 support missed CodeQL 2.27.0 and is expected in 2.27.1.
Reassess this exception if KAPT is introduced, and upgrade Kotlin together with
a compatible stable CodeQL release. This applicability decision is not a claim
that Kotlin 2.4.10 contains the upstream patch.

## Validation and future updates

After updating the catalog or constraints:

1. Run the unit tests and developer assembly from [BUILDING.md](BUILDING.md).
2. Generate the complete graph with the existing **Dependency Submission** workflow
   and inspect its JSON artifact for every affected coordinate and version.
   Checking only the app runtime omits vulnerable build and test dependencies.
3. Confirm the main-branch graph submission succeeds and read back Dependabot
   alerts. Do not dismiss alerts simply because a dependency is transitive or
   absent from the app runtime.
4. Run the attribution guard and its existing tests.

The public source and developer-build checks do not establish that an existing
signed APK contains these changes. Official QA and release delivery continue to
use the Jenkins pipeline described in the build guide.

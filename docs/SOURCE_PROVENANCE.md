# Source provenance

This public repository is based on the application source snapshot for **Expressive Launcher 1.0.12 / version code 13**. The current `main` branch also includes the **Expressive Bloom** branding update described below; the original snapshot is preserved by `source-v1.0.12-13`.

| Item | Identity |
| --- | --- |
| Application source revision used for the signed build | `ad7a5c336423df6edb198a700a050117808c96f6` |
| Lawnchair 16 baseline | [`eed2baf4efe4cf49540cf4ec474942dc743b83cc`](https://github.com/LawnchairLauncher/lawnchair/tree/eed2baf4efe4cf49540cf4ec474942dc743b83cc) |
| Required SystemUI submodule | [`e12acf0978875fc4adcebf46207b766106ffc92b`](https://github.com/LawnchairLauncher/platform_frameworks_libs_systemui/tree/e12acf0978875fc4adcebf46207b766106ffc92b) |
| Signed QA release | [`qa-v1.0.12-13`](https://github.com/denson9874/ExpressiveLauncher/releases/tag/qa-v1.0.12-13) |
| Public source tag | `source-v1.0.12-13` |
| APK SHA-256 | `778bb1720151c4b486ddc5d400fb0b69bc2c725cd692f2d246cf17dc6ebf88a1` |

## Publication snapshot

The application was developed in a separate working repository. Its source revision above identifies the build's recorded input; that local commit history was not imported into this repository. The initial public commit, `1ba2ee49b0f165dd1f2e67cf68323a51a13006ef`, and the `source-v1.0.12-13` tag contain the source snapshot with the publication documentation described below. The existing public repository history and original release assets are preserved.

The application implementation, resources, tests, build dependencies, Gradle files and wrapper were retained from the recorded source. Git normalized the Google Fonts catalog from CRLF to LF line endings; its JSON data is unchanged. Publication preparation adds the README, comparison graphics, build/download guides and readable third-party notices. It replaces workstation-specific paths in the Jenkins environment blocks and selected operations documents with `/path/to/...` placeholders. Configure those paths for your own environment before using the CI templates.

Inherited GitHub workflow and community configuration is retained under [`docs/reference/upstream-github`](reference/upstream-github/). It is reference material; it is not enabled as Expressive automation. The repository-root contribution guide now points to Expressive. Original license notices and source headers remain intact. No private signing material, developer local.properties or retained personal-device evidence is included.

## Expressive Bloom branding update

The `main` branch now carries the approved [Expressive Bloom artwork](assets/expressive/expressive-bloom.png), coordinated repository graphics, and updated Android and store icon resources. Builds made from this source use the new branding. The application version remains **1.0.12 / version code 13** in this public source baseline.

This branding change does not replace the historical signed APK identified above. Its source tag, package identity, checksum, validation results, and release downloads remain associated with the original build and icon. Each newly published QA or stable build supplies its own version and verification evidence through the existing release process.

## Get the complete source

```sh
git clone --recurse-submodules --branch source-v1.0.12-13 https://github.com/denson9874/ExpressiveLauncher.git
```

GitHub's normal source ZIP contains the parent repository but omits submodule contents. Recursive cloning retrieves the pinned SystemUI code required by `settings.gradle`. See [BUILDING.md](BUILDING.md).

The older `qa-v1.0.11-12` and `qa-v1.0.12-13` release tags were created while this repository held exports only. Their automatically generated source ZIPs still reflect that history. Use the new source tag or `main` for application code; download the APK and evidence from the release's named assets.

## Foundation and scope

Expressive inherits Launcher3 through Lawnchair's Android 16-derived baseline and targets Android 17 / API37 for its current app build. The separately staged full Android 17 Launcher3 core port is not complete. [ANDROID17_PORT.md](ANDROID17_PORT.md) records that distinction and upstream reference points.

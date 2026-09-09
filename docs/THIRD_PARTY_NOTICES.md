# Third-party notices

Expressive Launcher builds on AOSP Launcher3 and Lawnchair. Their copyright headers,
source notices, component licenses, and prebuilt library provenance remain in this
source snapshot. This page provides a readable index; each component keeps its
own applicable license.

## AOSP Launcher3 and Lawnchair

The retained repository [Apache License 2.0](../LICENSE.txt) includes these notices:

```text
Copyright (c) 2005-2008, The Android Open Source Project
Copyright (c) 2024, Lawnchair
```

- [AOSP Launcher3 source](https://android.googlesource.com/platform/packages/apps/Launcher3)
- [Lawnchair source](https://github.com/LawnchairLauncher/lawnchair)
- [Retained in-app notices for Launcher3 and Android compatibility libraries](../lawnchair/assets/license.html)
- [Android source revisions and integration provenance](ANDROID17_PORT.md)
- [Prebuilt AOSP library origins and build commands](../prebuilts/libs/README.md)
- [SystemUI dependency configuration](../.gitmodules); the dependency's own source notices remain with that repository.

Individual source files may contain additional copyright notices. Those notices
remain authoritative for their respective files.

## Calculator

The calculator component includes the [MIT License](../lawnchair/src/app/lawnchair/search/algorithms/data/calculator/LICENSE),
with the retained notice `Copyright (c) 2018 Keelar`.

## Google Sans Flex

The bundled [Google Sans Flex font](../lawnchair/res/font/googlesansflex_variable.ttf)
contains this copyright notice in its font metadata:

```text
Copyright 2015 Google LLC. All Rights Reserved.
```

The same metadata identifies the font license as **SIL Open Font License, Version
1.1** and links to [the Open Font License site](https://openfontlicense.org).
The readable [Google Sans Flex license](licenses/GoogleSansFlex-OFL.txt) retains
that bundled copyright notice followed by the complete, unmodified license text
from the [official Google Sans Flex project](https://github.com/googlefonts/googlesans-flex).
The official text also retains its project-author copyright notice.

The upstream license was retrieved from
[Google Sans Flex revision 6cffd50d9ff4ead23eda9dc7864eb9aed9fe304c](https://github.com/googlefonts/googlesans-flex/blob/6cffd50d9ff4ead23eda9dc7864eb9aed9fe304c/OFL.txt).
No font bytes were changed for this publication.

## Dependency notices

The source and build files retain the project's dependency declarations and
license-reporting configuration. Dependencies fetched during a build carry their
own licenses and notices. The font's SIL Open Font License and the calculator's
MIT License are separate from the repository's Apache License 2.0.

Product and organization names identify the original projects and integrations;
this project does not claim their endorsement.

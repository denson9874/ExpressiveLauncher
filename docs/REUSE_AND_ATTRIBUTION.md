# Reusing Expressive Launcher with attribution

Expressive Launcher is maintained by [Daryl Denson](https://github.com/denson9874)
and its contributors at [denson9874/ExpressiveLauncher](https://github.com/denson9874/ExpressiveLauncher).
The project builds on AOSP Launcher3, Lawnchair, and other credited components.
The [contribution index](SOURCE_PROVENANCE.md#expressive-contribution-index) identifies
selected Expressive additions separately from their upstream foundation.

## What redistribution requires

The existing [Apache License 2.0](../LICENSE.txt) continues to apply to code covered
by that license. Component-specific licenses remain in force; consult
[third-party notices](THIRD_PARTY_NOTICES.md) and the files you actually reuse.
This guide and [NOTICE](../NOTICE) do not introduce a new license.

For Apache-licensed work, section 4 requires redistributors to:

1. Give recipients a copy of the Apache license.
2. Put prominent notices in modified files stating that they were changed.
3. Retain relevant copyright, patent, trademark, and attribution notices in
   distributed derivative source, excluding notices unrelated to the parts reused.
4. Carry forward the relevant attribution from an included `NOTICE` in a readable
   location allowed by section 4(d): a distributed NOTICE file, accompanying
   source or documentation, or the usual third-party-notice display.

Those requirements apply as specified in the license. A splash screen, advertising
credit, clickable link, or a particular About-screen layout is not an additional
requirement. Private use does not trigger these redistribution conditions merely
because someone downloads or runs the software.

## A practical acknowledgment

When incorporating Expressive contributions, this acknowledgment makes their origin
easy to find. It supplements the applicable license and notices; it does not replace them:

```text
Includes work from Expressive Launcher by Daryl Denson and contributors.
https://github.com/denson9874/ExpressiveLauncher
Based on AOSP Launcher3 and Lawnchair; see the included licenses and notices.
```

For source reuse, retain existing headers, record the source commit or release,
and describe your changes. Preserving commit authors when cherry-picking and
linking to the original contribution are useful ways to keep a traceable record.
The project's contribution workflow is described in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Project identity and artwork

The Expressive Launcher name and Bloom identity identify this project. Reusing
software does not imply endorsement by its maintainers. Apache section 6 does not
grant trademark permission beyond customary identification of the work's origin
and reproduction of its notices. An independent fork should identify its own
maintainer and avoid presenting itself as an official Expressive release.

[APP_ICON.md](APP_ICON.md) records how the Bloom artwork was generated and exported.
This guide does not claim a registered trademark, exclusive copyright in purely
generated imagery, or revoke permissions already granted for distributed assets.

## What the repository protections do

The attribution check verifies the root and bundled Expressive notices, selected
source headers, and retained license texts. Run it with:

```sh
python3 ci/check_attribution.py
python3 -m unittest discover -s ci/tests -p 'test_check_attribution.py'
```

The GitHub workflow runs this check for changes to `main`. The maintainer is listed
in [CODEOWNERS](../.github/CODEOWNERS) for notices, provenance, and the guard itself.
GitHub's active [repository rules](https://github.com/denson9874/ExpressiveLauncher/rules)
are the authority for enforced branch and tag protections; CODEOWNERS alone does
not enforce approval. These controls protect this repository's record and catch
accidental omissions. They cannot control a separately hosted copy.

The copy at `lawnchair/assets/expressive-NOTICE.txt` uses the existing Lawnchair
asset directory so source builds can carry Expressive attribution with the app.
This source change does not retrofit an already published APK. Release validation
must verify the notice in each newly built distribution.

## If attribution appears to be missing

Record the other repository or download URL, its exact commit or version, the
matching Expressive files and revisions, and the notices supplied with that copy.
Check accompanying source, documentation, and third-party notices before concluding
that attribution was omitted. Contact the Expressive maintainer through the
[project repository](https://github.com/denson9874/ExpressiveLauncher) with that
evidence; avoid posting personal information or unsupported accusations.

Public code remains downloadable and reusable under its applicable licenses.
Existing Apache grants cannot simply be withdrawn, and this new NOTICE does not
retroactively change earlier snapshots. Formal infringement claims need an
assessment of the particular work, ownership, license, and distribution.

## Authoritative references

- [Apache License 2.0, especially sections 2, 4, and 6](https://www.apache.org/licenses/LICENSE-2.0)
- [Apache guidance on applying notices and SPDX headers](https://www.apache.org/foundation/license-faq#Apply-My-Software)
- [GitHub guidance on licensing public repositories](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)

# Copyright 2026 Daryl Denson and Expressive Launcher contributors.
# SPDX-License-Identifier: Apache-2.0
# https://github.com/denson9874/ExpressiveLauncher

"""Exercise Jenkins source preparation across the Android directory migration."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    'prepare_build', Path(__file__).parents[1] / 'prepare_build.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


def git(directory, *args):
    return subprocess.check_output(
        ['git', '-C', str(directory), *args], text=True, stderr=subprocess.PIPE).strip()


def initialize(directory):
    directory.mkdir()
    git(directory, 'init', '--quiet')
    git(directory, 'config', 'user.name', 'Layout Test')
    git(directory, 'config', 'user.email', 'layout-test@example.invalid')


class SourceLayoutTests(unittest.TestCase):
    def test_pinned_submodule_worktrees_across_both_layouts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            upstream = root / 'upstream'
            initialize(upstream)
            (upstream / 'source.txt').write_text('pinned source\n')
            git(upstream, 'add', '.')
            git(upstream, 'commit', '--quiet', '-m', 'Pinned source')
            pinned = git(upstream, 'rev-parse', 'HEAD')

            repository = root / 'repository'
            initialize(repository)
            module = 'platform_frameworks_libs_systemui'
            git(repository, '-c', 'protocol.file.allow=always', 'submodule',
                'add', '--name', module, str(upstream), module)
            git(repository, 'commit', '--quiet', '-m', 'Original layout')
            old_revision = git(repository, 'rev-parse', 'HEAD')
            (repository / 'android').mkdir()
            git(repository, 'mv', module, 'android/' + module)
            git(repository, 'commit', '--quiet', '-am', 'Group Android project')
            new_revision = git(repository, 'rev-parse', 'HEAD')

            # Each repository layout must prepare both current and historical
            # source revisions, with the exact pinned module and clean source.
            for current in (new_revision, old_revision):
                git(repository, 'checkout', '--quiet', current)
                git(repository, 'submodule', 'sync')
                git(repository, '-c', 'protocol.file.allow=always', 'submodule',
                    'update', '--init')
                current_path = git(repository, 'config', '-f', '.gitmodules',
                                   '--get', 'submodule.' + module + '.path')
                for revision, expected_path in (
                    (old_revision, module), (new_revision, 'android/' + module)
                ):
                    with self.subTest(current=current, source=revision):
                        source = root / ('source-' + current[:7] + '-' + revision[:7])
                        result = prepare.prepare_source(repository, source, revision)
                        self.assertEqual(result, pinned)
                        self.assertEqual(git(source, 'rev-parse', 'HEAD'), revision)
                        self.assertEqual(git(source / expected_path, 'rev-parse', 'HEAD'), pinned)
                        self.assertEqual((source / expected_path / 'source.txt').read_text(),
                                         'pinned source\n')
                        self.assertEqual(git(source, 'status', '--porcelain'), '')
                        self.assertEqual(git(repository, 'rev-parse', 'HEAD'), current)
                        self.assertEqual(git(repository / current_path, 'rev-parse', 'HEAD'), pinned)


if __name__ == '__main__':
    unittest.main()

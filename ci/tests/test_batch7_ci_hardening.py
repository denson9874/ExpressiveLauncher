"""Batch 7 CI/CD, signing script, and Jenkins security hardening regressions (EL-D2, D5, D8, D9)."""

import importlib.util
import io
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).parents[2]
WORKFLOWS_DIR = ROOT / ".github" / "workflows"
HARDENED_WORKFLOWS = (
    "build_release_apk.yml",
    "ci.yml",
    "release_update.yml",
    "build_expressive_play_bundle.yml",
)

INSTALL_LOCAL_SPEC = importlib.util.spec_from_file_location(
    "jenkins_install_local",
    ROOT / "ci" / "jenkins" / "install_local.py",
)
install_local = importlib.util.module_from_spec(INSTALL_LOCAL_SPEC)
INSTALL_LOCAL_SPEC.loader.exec_module(install_local)


def extract_run_blocks(workflow_text):
    """Return every shell `run:` block body from a GitHub Actions workflow YAML."""
    blocks = []
    lines = workflow_text.splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        match = re.match(r"^(\s*)(?:-\s*)?run:\s*(.*)$", line)
        if not match:
            index += 1
            continue
        indent = len(match.group(1))
        rest = match.group(2).strip()
        if rest in ("|", "|-", "|+", ">", ">-", ">+"):
            body = []
            index += 1
            while index < len(lines):
                current = lines[index]
                if current.strip() == "":
                    body.append(current)
                    index += 1
                    continue
                current_indent = len(current) - len(current.lstrip(" "))
                if current_indent <= indent:
                    break
                body.append(current)
                index += 1
            blocks.append("\n".join(body))
        else:
            blocks.append(rest)
            index += 1
    return blocks


class WorkflowSecurityHardeningTests(unittest.TestCase):
    def test_no_hardcoded_gradle_cache_encryption_key_in_workflows(self):
        for path in sorted(WORKFLOWS_DIR.glob("*.yml")):
            text = path.read_text(encoding="utf-8")
            with self.subTest(workflow=path.name):
                self.assertNotIn("Da25KUVSE5jbGds2zXmfXw==", text)
        for name in ("build_release_apk.yml", "ci.yml"):
            text = (WORKFLOWS_DIR / name).read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertIn("cache-encryption-key: ${{ secrets.GRADLE_CACHE_ENCRYPTION_KEY }}", text)

    def test_no_secrets_or_inputs_interpolated_directly_in_run_blocks(self):
        forbidden = re.compile(r"\$\{\{\s*(?:secrets|inputs|github\.event\.inputs)\.")
        for name in HARDENED_WORKFLOWS:
            text = (WORKFLOWS_DIR / name).read_text(encoding="utf-8")
            run_blocks = extract_run_blocks(text)
            self.assertTrue(run_blocks, f"Expected run blocks in {name}")
            for block in run_blocks:
                with self.subTest(workflow=name, block=block[:80]):
                    self.assertIsNone(
                        forbidden.search(block),
                        f"{name} interpolates secrets/inputs directly in shell run block: {block}",
                    )

    def test_signing_workflows_use_umask_077_printf_and_always_cleanup(self):
        for name in HARDENED_WORKFLOWS:
            text = (WORKFLOWS_DIR / name).read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertIn("umask 077", text)
                self.assertIn("printf ", text)
                self.assertIn("if: always()", text)
                self.assertIn("shred -uz", text)
                self.assertIn("keystore.properties", text)
                self.assertIn("key.jks", text)


class JenkinsAndSigningScriptHardeningTests(unittest.TestCase):
    def test_jenkinsfiles_have_no_hardcoded_developer_home_paths(self):
        for name in ("Jenkinsfile.build", "Jenkinsfile.release-build"):
            text = (ROOT / "ci" / name).read_text(encoding="utf-8")
            with self.subTest(jenkinsfile=name):
                self.assertNotIn("/Users/densond", text)
                self.assertIn("env.HOME", text)
                self.assertIn("env.WORKSPACE", text)

    def test_bootstrap_groovy_creates_secret_files_atomically_0600(self):
        text = (ROOT / "ci" / "jenkins" / "bootstrap.groovy").read_text(encoding="utf-8")
        self.assertIn("PosixFilePermissions.fromString('rw-------')", text)
        self.assertIn("Files.createTempFile", text)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", text)
        self.assertNotIn("credentialFile.text =", text)
        self.assertNotIn("workerSecret.text =", text)

    def test_signing_scripts_pass_keytool_passwords_via_env_not_argv(self):
        for name in (
            "configure-expressive-release-signing.sh",
            "configure-play-developer-signing.sh",
        ):
            path = ROOT / "scripts" / name
            text = path.read_text(encoding="utf-8")
            with self.subTest(script=name):
                self.assertIn("-storepass:env KEYTOOL_SIGNING_PASSWORD", text)
                self.assertIn("-keypass:env KEYTOOL_SIGNING_PASSWORD", text)
                self.assertIsNone(re.search(r"-storepass\s+\"", text))
                self.assertIsNone(re.search(r"-keypass\s+\"", text))
                syntax = subprocess.run(
                    ["/bin/zsh", "-n", str(path)],
                    capture_output=True,
                    text=True,
                    timeout=15,
                    check=False,
                )
                self.assertEqual(0, syntax.returncode, syntax.stderr)

    def test_install_local_write_atomic_sets_mode_0600_and_cleans_up_temp_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "config" / "bootstrap.properties"
            install_local.write_atomic(target, b"adminUser=test\n", mode=0o600)
            self.assertEqual(b"adminUser=test\n", target.read_bytes())
            self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
            self.assertEqual([target.name], [entry.name for entry in target.parent.iterdir()])

            # Overwriting atomically preserves target on failure and leaves no stray temp files.
            with patch.object(install_local.os, "replace", side_effect=OSError("simulated replace failure")):
                with self.assertRaises(OSError):
                    install_local.write_atomic(target, b"new-content", mode=0o600)
            self.assertEqual(b"adminUser=test\n", target.read_bytes())
            self.assertEqual([target.name], [entry.name for entry in target.parent.iterdir()])

    def test_install_local_download_agent_jar_uses_atomic_write_and_rejects_empty(self):
        class FakeResponse(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *args):
                self.close()

        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "bin" / "agent.jar"
            with patch.object(
                install_local.urllib.request,
                "urlopen",
                return_value=FakeResponse(b"PK\x03\x04fake-jar"),
            ) as mocked_open:
                install_local.download_agent_jar("http://127.0.0.1:8091/jnlpJars/agent.jar", destination)
            mocked_open.assert_called_once_with("http://127.0.0.1:8091/jnlpJars/agent.jar", timeout=60)
            self.assertEqual(b"PK\x03\x04fake-jar", destination.read_bytes())
            self.assertEqual(0o644, stat.S_IMODE(destination.stat().st_mode))
            self.assertEqual(["agent.jar"], [entry.name for entry in destination.parent.iterdir()])

            with patch.object(
                install_local.urllib.request,
                "urlopen",
                return_value=FakeResponse(b""),
            ):
                with self.assertRaises(SystemExit):
                    install_local.download_agent_jar("http://127.0.0.1:8091/jnlpJars/agent.jar", destination)
            # Previous valid agent.jar remains intact if an empty download arrives.
            self.assertEqual(b"PK\x03\x04fake-jar", destination.read_bytes())


if __name__ == "__main__":
    unittest.main()

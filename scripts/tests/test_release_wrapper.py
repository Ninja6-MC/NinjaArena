"""scripts/release.sh refuses bad input before it touches the network, and release.ps1
hands its arguments to it intact.

The bash cases run wherever bash does. The PowerShell cases need Windows PowerShell and
Git Bash and are skipped anywhere else. Every case stops on a known refusal, or on a dry
run, before any tag, push or dispatch.
"""

import os
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
BASH = shutil.which("bash")
POWERSHELL = shutil.which("powershell")
GIT_BASH = any(Path(base, "Git", "bin", "bash.exe").is_file()
               for base in filter(None, (os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)"))))


@unittest.skipUnless(BASH and os.name != "nt", "needs bash")
class ReleaseScriptTests(unittest.TestCase):
    def run_script(self, *args):
        result = subprocess.run([BASH, str(SCRIPTS / "release.sh"), *args], capture_output=True, text=True,
                                timeout=60, stdin=subprocess.DEVNULL, cwd=SCRIPTS.parent)
        return result.returncode, result.stdout + result.stderr

    def test_versions_outside_the_tag_grammar_are_refused(self):
        for version in ("1.0.0", "0.5", "v0.5.0", "0.05.0", "0.5.0-rc.0", "0.5.0-dev.1"):
            with self.subTest(version=version):
                code, output = self.run_script(version, "--dry-run")
                self.assertEqual(1, code)
                self.assertIn("is not a valid version", output)
                code, output = self.run_script("publish", version, "99", "--dry-run")
                self.assertEqual(1, code)
                self.assertIn("is not a valid version", output)

    def test_publish_refuses_a_bad_run_id_or_destination(self):
        for run_id in ("0", "abc", "-1", "12 3"):
            with self.subTest(run_id=run_id):
                code, output = self.run_script("publish", "0.5.0", run_id, "--dry-run")
                self.assertEqual(1, code)
                self.assertIn("is not a Release Candidate run ID", output)
        for destinations in ("modrinth", "github,shared-state", "github,github", ""):
            with self.subTest(destinations=destinations):
                code, output = self.run_script("publish", "0.5.0", "99", "--destinations", destinations, "--dry-run")
                self.assertEqual(1, code)
                self.assertIn("destinations must be", output)

    def test_unknown_options_are_refused(self):
        code, output = self.run_script("publish", "0.5.0", "99", "--attest", "github")
        self.assertEqual(1, code)
        self.assertIn("unknown option", output)
        code, _ = self.run_script("0.5.0", "--force")
        self.assertEqual(2, code)


@unittest.skipUnless(os.name == "nt" and POWERSHELL and GIT_BASH, "needs Windows PowerShell and Git Bash")
class WrapperTests(unittest.TestCase):
    def run_wrapper(self, *args):
        # -Command, not -File: an unquoted a,b is parsed as an array only at a PowerShell prompt.
        line = "& '{}' {}".format(SCRIPTS / "release.ps1", " ".join(args))
        result = subprocess.run([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", line],
                                capture_output=True, text=True, timeout=120)
        return result.returncode, result.stdout + result.stderr

    def test_unquoted_destination_list_reaches_release_sh_intact(self):
        code, output = self.run_wrapper("publish", "0.5.0", "99", "--destinations", "github,shared-state", "--dry-run")
        self.assertEqual(1, code)
        self.assertIn("not 'github,shared-state'", output)

    def test_bad_version_is_refused_through_the_wrapper(self):
        code, output = self.run_wrapper("1.0.0", "--dry-run")
        self.assertEqual(1, code)
        self.assertIn("is not a valid version", output)


if __name__ == "__main__":
    unittest.main()

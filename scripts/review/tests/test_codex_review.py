"""Bridge contract tests: use a fake Codex executable, never application services."""
import fcntl
import importlib.util
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import argparse

RUNNER = Path(__file__).resolve().parents[1] / "codex_review.py"
spec = importlib.util.spec_from_file_location("codex_review", RUNNER)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)

PASS = {"status": "pass", "summary": "검토 범위 내 차단 지적 없음", "findings": [], "limitations": ["실제 서비스 호출 안 함"]}


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="salus-review-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        (self.root / ".gitignore").write_text(".codex-review/\n")
        (self.root / "AGENTS.md").write_text("Read-only review. No deployment.\n")
        (self.root / "a.py").write_text("def total(values):\n    return sum(values)\n")
        self.git("add", ".gitignore", "AGENTS.md", "a.py")
        self.git("-c", "user.name=Review Fixture", "-c", "user.email=review@example.invalid",
                 "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "fixture")
        (self.root / "a.py").write_text("def total(values):\n    return sum(values) + 1\n")
        (self.root / "brief.md").write_text("Test task. No Git or deployment permission.\n")
        self.fake = self.root / "fake-codex"
        self.calls = self.root / "call-count"
        self.fake_codex()

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)

    def fake_codex(self, value=PASS, prelude="", final=""):
        self.fake.write_text("#!" + sys.executable + "\n" +
                            "import json, pathlib, sys, time\n" +
                            "args=sys.argv[1:]\n" +
                            "if args == ['login', 'status']: print('Logged in using ChatGPT'); sys.exit(0)\n" +
                            "assert args[args.index('--sandbox')+1]=='read-only'\n" +
                            "assert '--ephemeral' in args and '--ignore-user-config' in args\n" +
                            "assert 'approval_policy=\"never\"' in args\n" +
                            "assert not any('bypass' in arg for arg in args)\n" +
                            "assert pathlib.Path.cwd()==pathlib.Path(args[args.index('--cd')+1]).resolve()\n" +
                            "assert not pathlib.Path.cwd().is_relative_to(pathlib.Path(" + repr(str(self.root)) + "))\n" +
                            "with pathlib.Path(" + repr(str(self.calls)) + ").open('a') as f: f.write('call\\n')\n" +
                            prelude + "\n" +
                            "pathlib.Path(args[args.index('--output-last-message')+1]).write_text(json.dumps(" + repr(value) + "))\n" +
                            final + "\n")
        self.fake.chmod(0o700)

    def run_bridge(self, *extra, paths=None, job="task"):
        result = subprocess.run([sys.executable, str(RUNNER), "--repo", str(self.root),
                                 "--job", job, "--brief", "brief.md", "--codex-bin", str(self.fake),
                                 "--model", "fixture-model", "--no-auto-retry",
                                 *extra, "--paths", *(paths or ["a.py"])], capture_output=True, text=True)
        self.assertTrue(result.stdout, result.stderr)
        return result.returncode, json.loads(result.stdout)

    def count(self):
        return len(self.calls.read_text().splitlines()) if self.calls.exists() else 0

    def test_snapshot_has_only_explicit_inputs_and_dry_run_does_not_call_codex(self):
        (self.root / ".env.beta").write_text("PRIVATE_SENTINEL=must_not_copy\n")
        (self.root / "unrelated.txt").write_text("unrelated WIP")
        code, result = self.run_bridge("--dry-run")
        self.assertEqual(code, 0)
        snapshot = Path(result["snapshot"])
        self.assertTrue((snapshot / "a.py").is_file())
        self.assertTrue((snapshot / "AGENTS.md").is_file())
        self.assertFalse((snapshot / ".env.beta").exists())
        self.assertFalse((snapshot / "unrelated.txt").exists())
        self.assertFalse((snapshot / ".git").exists())
        self.assertEqual(self.count(), 0)
        self.assertEqual(stat.S_IMODE((self.root / ".codex-review").stat().st_mode), 0o700)

    def test_secrets_traversal_and_symlinks_are_rejected(self):
        (self.root / ".env.beta").write_text("PRIVATE_SENTINEL=must_not_copy\n")
        (self.root / "linked.py").symlink_to(self.root / "a.py")
        for path in [".env.beta", "../outside.py", "linked.py", "frontend/src/secrets.js"]:
            with self.subTest(path=path):
                code, result = self.run_bridge(paths=[path])
                self.assertEqual(code, 2)
                self.assertEqual(result["status"], "error")
                self.assertNotIn("PRIVATE_SENTINEL", json.dumps(result))
        self.assertEqual(self.count(), 0)

    def test_pass_is_cached_without_another_model_call(self):
        code, first = self.run_bridge()
        self.assertEqual(code, 0)
        code, second = self.run_bridge()
        self.assertEqual(code, 0)
        self.assertTrue(second["cached"])
        self.assertEqual(first["report"], second["report"])
        self.assertEqual(self.count(), 1)

    def test_three_changed_inputs_consume_limit_and_fourth_stops(self):
        for round_number in range(1, 4):
            (self.root / "a.py").write_text("value = " + str(round_number) + "\n")
            code, result = self.run_bridge()
            self.assertEqual(code, 0)
            self.assertEqual(result["calls"], round_number)
        (self.root / "a.py").write_text("value = 4\n")
        code, result = self.run_bridge()
        self.assertEqual(code, 4)
        self.assertEqual(result["status"], "needs_human")
        self.assertEqual(self.count(), 3)

    def test_findings_are_returned_for_claude_to_evaluate(self):
        finding = {"priority": "P2", "path": "a.py", "line": 2, "problem": "합계에 1이 추가됨",
                   "evidence": "[1, 2]의 결과가 4", "suggestion": "목표 계약을 확인하고 수정"}
        self.fake_codex({**PASS, "status": "changes_requested", "findings": [finding]})
        code, result = self.run_bridge()
        self.assertEqual(code, 1)
        self.assertEqual(result["review"]["findings"][0]["evidence"], finding["evidence"])

    def test_pass_with_unresolved_blocker_is_not_accepted(self):
        finding = {"priority": "P1", "path": "a.py", "line": 2, "problem": "problem",
                   "evidence": "evidence", "suggestion": "fix"}
        self.fake_codex({**PASS, "findings": [finding]})
        code, result = self.run_bridge()
        self.assertEqual(code, 2)
        self.assertEqual(result["status"], "error")

    def test_invalid_output_is_an_error_and_consumes_call(self):
        self.fake_codex({"status": "pass"})
        code, result = self.run_bridge()
        self.assertEqual(code, 2)
        state = json.loads((self.root / ".codex-review/task/state.json").read_text())
        self.assertEqual(state["calls"], 1)
        self.assertEqual(state["status"], "error")

    def test_edit_during_review_invalidates_result(self):
        self.fake_codex(prelude="pathlib.Path(" + repr(str(self.root / "a.py")) + ").write_text('changed during review\\n')")
        code, result = self.run_bridge()
        self.assertEqual(code, 3)
        self.assertEqual(result["status"], "stale")
        state = json.loads((self.root / ".codex-review/task/state.json").read_text())
        self.assertNotIn("result", state)

    def test_timeout_is_not_a_pass(self):
        self.fake_codex(prelude="time.sleep(30)")
        code, result = self.run_bridge("--timeout", "1")
        self.assertEqual(code, 2)
        self.assertEqual(result["status"], "error")

    def test_simultaneous_same_job_is_rejected(self):
        job = self.root / ".codex-review/task"
        job.mkdir(parents=True)
        with (job / "lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            code, result = self.run_bridge()
            self.assertEqual(code, 2)
            self.assertEqual(result["status"], "error")
        self.assertEqual(self.count(), 0)

    def test_reply_and_previous_review_are_in_next_snapshot(self):
        self.run_bridge()
        (self.root / "reply.md").write_text("근거: 요구사항에 따라 합계 +1이 맞음")
        code, result = self.run_bridge("--reply", "reply.md", "--dry-run")
        self.assertEqual(code, 0)
        review_data = Path(result["snapshot"]) / ".review"
        self.assertTrue((review_data / "previous-review.json").is_file())
        self.assertIn("요구사항", (review_data / "implementer-reply.md").read_text())

    def test_new_and_deleted_files_have_both_sides(self):
        (self.root / "a.py").unlink()
        (self.root / "new.py").write_text("value = 1\n")
        code, result = self.run_bridge("--dry-run", paths=["a.py", "new.py"])
        self.assertEqual(code, 0)
        snapshot = Path(result["snapshot"])
        self.assertFalse((snapshot / "a.py").exists())
        self.assertTrue((snapshot / ".review/before/a.py").exists())
        self.assertTrue((snapshot / "new.py").exists())

    def test_unchanged_scope_is_not_reviewed_as_a_false_pass(self):
        code, result = self.run_bridge(paths=["AGENTS.md"])
        self.assertEqual(code, 2)
        self.assertEqual(self.count(), 0)

    def args(self, **overrides):
        values = dict(repo=str(self.root), job="task", brief="brief.md", paths=["a.py"],
                      include=[], reply=None, base="HEAD", timeout=10, codex_bin=str(self.fake),
                      dry_run=False, model="fixture-model", status=False, cancel=False,
                      resume=False, worker=False, no_auto_retry=False)
        values.update(overrides)
        return argparse.Namespace(**values)

    def state(self):
        return json.loads((self.root / ".codex-review/task/state.json").read_text())

    def retry_clock(self):
        clock = mock.Mock()
        clock.now = 100000.0
        clock.time.side_effect = lambda: clock.now
        clock.sleep.side_effect = lambda seconds: setattr(clock, "now", clock.now + seconds)
        return clock

    def test_third_failure_queues_one_hour_without_consuming_reviews(self):
        self.fake_codex(prelude="print('usage limit reached', file=sys.stderr); sys.exit(1)")
        clock = self.retry_clock()
        with mock.patch.object(bridge.time, "time", clock.time), \
                mock.patch.object(bridge, "retry_sleep", clock.sleep), \
                mock.patch.object(bridge, "launch_worker") as launch:
            self.assertEqual(bridge.run_review(self.args()), 5)
        state = self.state()
        self.assertEqual(self.count(), 3)
        self.assertEqual(state["reviews"], 0)
        self.assertEqual(state["failures"], 3)
        self.assertEqual(state["next_retry_at"], clock.now + 3600)
        self.assertEqual(state["model"], "fixture-model")
        launch.assert_called_once()

    def test_worker_failure_waits_another_hour_then_recovers_same_model(self):
        self.fake_codex(prelude="print('rate limit exceeded', file=sys.stderr); sys.exit(1)")
        clock = self.retry_clock()
        with mock.patch.object(bridge.time, "time", clock.time), \
                mock.patch.object(bridge, "retry_sleep", clock.sleep), \
                mock.patch.object(bridge, "launch_worker"):
            bridge.run_review(self.args())
        first_retry_at = self.state()["next_retry_at"]
        original = bridge.execute
        retries = []

        def execute(*args):
            retries.append((clock.now, args[0][args[0].index("--model") + 1]))
            if len(retries) == 2:
                self.fake_codex()
            return original(*args)

        with mock.patch.object(bridge.time, "time", clock.time), \
                mock.patch.object(bridge, "retry_sleep", clock.sleep), \
                mock.patch.object(bridge, "execute", side_effect=execute), \
                mock.patch.object(bridge, "launch_worker") as launch:
            self.assertEqual(bridge.run_review(self.args(worker=True)), 0)
        self.assertEqual(retries, [(first_retry_at, "fixture-model"), (first_retry_at + 3600, "fixture-model")])
        self.assertEqual(self.state()["failures"], 0)
        self.assertEqual(self.state()["reviews"], 1)
        self.assertEqual(self.count(), 5)
        launch.assert_not_called()

    def queued(self):
        self.fake_codex(prelude="print('usage limit reached', file=sys.stderr); sys.exit(1)")
        clock = self.retry_clock()
        with mock.patch.object(bridge.time, "time", clock.time), \
                mock.patch.object(bridge, "retry_sleep", clock.sleep), \
                mock.patch.object(bridge, "launch_worker"):
            bridge.run_review(self.args())

    def test_changed_input_stops_pending_retry_before_model_call(self):
        self.queued()
        (self.root / "a.py").write_text("changed while waiting\n")
        self.assertEqual(bridge.run_review(self.args(worker=True)), 3)
        self.assertEqual(self.count(), 3)
        self.assertEqual(self.state()["status"], "stale")

    def test_cancel_stops_pending_retry_and_status_does_not_call_model(self):
        self.queued()
        self.assertEqual(bridge.run_review(self.args(cancel=True, brief=None, paths=None)), 0)
        self.assertEqual(bridge.run_review(self.args(worker=True)), 2)
        self.assertEqual(self.state()["status"], "cancelled")
        self.assertEqual(bridge.run_review(self.args(status=True, brief=None, paths=None)), 0)
        self.assertEqual(self.count(), 3)

    def test_detached_worker_completes_after_parent_exits(self):
        self.queued()
        path = self.root / ".codex-review/task/state.json"
        state = self.state()
        state["next_retry_at"] = time.time() - 1
        path.write_text(json.dumps(state))
        self.fake_codex()
        process = bridge.launch_worker(self.args(), self.root, path.parent, "fixture-model")
        try:
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and self.state()["status"] == "waiting_retry":
                time.sleep(0.1)
            while time.monotonic() < deadline and self.state()["status"] == "running":
                time.sleep(0.1)
            self.assertEqual(self.state()["status"], "pass")
            self.assertEqual(self.count(), 4)
        finally:
            (path.parent / "cancel").touch()
            process.wait(timeout=10)

    def test_bare_resume_uses_saved_scope_and_model(self):
        self.queued()
        self.fake_codex()
        args = self.args(resume=True, brief=None, paths=None, model=None, codex_bin="not-installed")
        self.assertEqual(bridge.run_review(args), 0)
        self.assertEqual(args.paths, ["a.py"])
        self.assertEqual(args.model, "fixture-model")
        self.assertEqual(self.count(), 4)

    def test_explicit_resume_clears_pause_but_does_not_review_changed_input(self):
        self.queued()
        bridge.run_review(self.args(cancel=True, brief=None, paths=None))
        self.assertEqual(bridge.run_review(self.args(worker=True)), 2)
        (self.root / "a.py").write_text("changed after pause\n")
        self.assertEqual(bridge.run_review(self.args(resume=True, brief=None, paths=None, model=None)), 3)
        self.assertEqual(self.count(), 3)
        self.assertFalse((self.root / ".codex-review/task/cancel").exists())

    def test_resume_without_saved_request_does_not_start_new_job(self):
        with self.assertRaises(bridge.ReviewError):
            bridge.run_review(self.args(resume=True, brief=None, paths=None))
        self.assertEqual(self.count(), 0)

    def test_unknown_execution_error_is_not_retried(self):
        self.fake_codex(prelude="print('invalid configuration', file=sys.stderr); sys.exit(1)")
        with self.assertRaises(bridge.ReviewError):
            bridge.run_review(self.args())
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.state()["status"], "error")

    def test_api_key_auth_stops_without_model_call(self):
        self.fake.write_text("#!" + sys.executable + "\nprint('Logged in using an API key')\n")
        with self.assertRaises(bridge.ReviewError):
            bridge.run_review(self.args())
        self.assertEqual(self.count(), 0)

    def test_job_cannot_switch_models(self):
        bridge.run_review(self.args())
        with self.assertRaises(bridge.ReviewError):
            bridge.run_review(self.args(model="different-model"))
        self.assertEqual(self.count(), 1)


if __name__ == "__main__":
    unittest.main()

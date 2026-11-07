"""Real local child processes exercise cancellation without media tools or APIs."""

import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from livecut import media


class MediaProcessGuardTests(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.children = []
        self.captures = []
        popen = subprocess.Popen

        def start(*args, **kwargs):
            self.captures.extend((kwargs["stdout"], kwargs["stderr"]))
            child = popen(*args, **kwargs)
            self.children.append(child)
            return child

        patcher = patch("livecut.media.subprocess.Popen", side_effect=start)
        self.popen = patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.stop_stragglers)

    def stop_stragglers(self) -> None:
        # Keep failures in these tests from leaving a synthetic child behind.
        for child in self.children:
            if child.poll() is None:
                child.kill()
            child.communicate(timeout=5)

    def run_child(self, program, *, guard=lambda: None, timeout=5, arguments=()):
        return media._run_process(
            [getattr(sys, "_base_executable", sys.executable), "-u", "-c", program,
             *map(str, arguments)],
            timeout_seconds=timeout,
            failure_code="TRANSCODE_FAILED",
            guard=guard,
        )

    def assert_reaped(self) -> None:
        self.assertEqual(1, len(self.children))
        child = self.children[0]
        self.assertIsNotNone(child.returncode)
        self.assertEqual(child.returncode, child.wait(timeout=0))
        self.assertIsNone(child.stdout)
        self.assertIsNone(child.stderr)
        self.assertTrue(all(capture.closed for capture in self.captures))
        if os.name != "nt":
            with self.assertRaises(ChildProcessError):
                os.waitpid(child.pid, os.WNOHANG)

    def test_verbose_child_captures_both_streams_and_retains_output_across_checks(self):
        checks = []
        result = self.run_child(
            "import os, sys, time; "
            "sys.stdout.write('stdout' * 50000); sys.stdout.flush(); "
            "sys.stderr.write('stderr' * 50000); sys.stderr.flush(); "
            "time.sleep(.35); os.write(1, b'\\xfftail'); os.write(2, b'\\xfftail')",
            guard=lambda: checks.append(time.monotonic()),
        )
        self.assertEqual(0, result.returncode)
        self.assertEqual("stdout" * 50000 + "\ufffdtail", result.stdout)
        self.assertEqual("stderr" * 50000 + "\ufffdtail", result.stderr)
        self.assertGreaterEqual(len(checks), 4)
        self.assert_reaped()
        kwargs = self.popen.call_args.kwargs
        self.assertFalse(kwargs["shell"])
        self.assertEqual(subprocess.DEVNULL, kwargs["stdin"])

    def test_silent_child_stops_for_cancel_lease_loss_and_keyboard_interrupt(self):
        for error in (RuntimeError("cancelled"), OSError("lease lost"), KeyboardInterrupt()):
            with self.subTest(error=type(error).__name__):
                self.children.clear()
                started = self.root / "started"
                late = self.root / "late"
                started.unlink(missing_ok=True)
                late.unlink(missing_ok=True)

                def guard():
                    if started.exists():
                        raise error

                before = time.monotonic()
                with self.assertRaises(type(error)) as raised:
                    self.run_child(
                        "import os, sys, time; from pathlib import Path; "
                        "Path(sys.argv[1]).write_text(str(os.getpid())); "
                        "time.sleep(.6); Path(sys.argv[2]).write_text('late completion')",
                        guard=guard,
                        arguments=(started, late),
                    )
                self.assertIs(error, raised.exception)
                self.assertLess(time.monotonic() - before, 1.5)
                self.assertEqual(str(self.children[0].pid), started.read_text())
                self.assert_reaped()
                time.sleep(.65)
                self.assertFalse(late.exists())

    def test_verbose_sleeping_child_is_cancelled_without_output_deadlock(self):
        started = self.root / "started"
        cancelled = RuntimeError("cancelled noisy encoder")

        def guard():
            if started.exists():
                raise cancelled

        with self.assertRaises(RuntimeError) as raised:
            self.run_child(
                "import sys, time; from pathlib import Path; "
                "sys.stdout.write('o' * 500000); sys.stdout.flush(); "
                "sys.stderr.write('e' * 500000); sys.stderr.flush(); "
                "Path(sys.argv[1]).write_text('ready'); time.sleep(10)",
                arguments=(started,), guard=guard,
            )
        self.assertIs(cancelled, raised.exception)
        self.assert_reaped()

    def test_timeout_reaps_child_and_prevents_late_completion(self):
        started = self.root / "started"
        late = self.root / "late"
        with self.assertRaises(media.MediaError) as raised:
            self.run_child(
                "import os, sys, time; from pathlib import Path; "
                "Path(sys.argv[1]).write_text(str(os.getpid())); "
                "time.sleep(.7); Path(sys.argv[2]).write_text('late completion')",
                timeout=.3, arguments=(started, late),
            )
        self.assertEqual("PROCESS_TIMEOUT", raised.exception.code)
        self.assertEqual(str(self.children[0].pid), started.read_text())
        self.assert_reaped()
        time.sleep(.75)
        self.assertFalse(late.exists())

    @unittest.skipIf(os.name == "nt", "Windows terminate is already an unignorable kill")
    def test_ignoring_terminate_escalates_to_kill_and_reaps(self):
        started = self.root / "started"

        def guard():
            if started.exists():
                raise RuntimeError("cancelled")

        with self.assertRaisesRegex(RuntimeError, "cancelled"):
            self.run_child(
                "import signal, sys, time; from pathlib import Path; "
                "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                "Path(sys.argv[1]).write_text('ready'); time.sleep(10)",
                guard=guard, arguments=(started,),
            )
        self.assert_reaped()
        self.assertEqual(-9, self.children[0].returncode)

    def test_guard_failure_before_launch_retains_original_error(self):
        error = OSError("lease lost before launch")

        def guard():
            raise error

        with self.assertRaises(OSError) as raised:
            self.run_child("print('never runs')", guard=guard)
        self.assertIs(error, raised.exception)
        self.popen.assert_not_called()

    def test_guard_time_counts_towards_deadline_before_launch(self):
        def guard():
            time.sleep(.04)

        with self.assertRaises(media.MediaError) as raised:
            self.run_child("print('never runs')", guard=guard, timeout=.02)
        self.assertEqual("PROCESS_TIMEOUT", raised.exception.code)
        self.popen.assert_not_called()

    def test_guard_time_after_child_exit_cannot_return_late_success(self):
        def guard():
            if self.children and self.children[0].poll() is not None:
                time.sleep(.4)

        with self.assertRaises(media.MediaError) as raised:
            self.run_child("print('completed')", guard=guard, timeout=.3)
        self.assertEqual("PROCESS_TIMEOUT", raised.exception.code)
        self.assert_reaped()

    def test_inherited_capture_handles_do_not_delay_wrapper_cancellation(self):
        started = self.root / "descendant-pid"
        release = self.root / "release-descendant"
        stopped = self.root / "descendant-stopped"
        error = RuntimeError("cancelled wrapper")
        descendant = (
            "import os, sys, time\nfrom pathlib import Path\n"
            "started, release, stopped = map(Path, sys.argv[1:])\n"
            "sys.stdout.write('descendant stdout'); sys.stdout.flush()\n"
            "sys.stderr.write('descendant stderr'); sys.stderr.flush()\n"
            "started.write_text(str(os.getpid()))\n"
            "deadline = time.monotonic() + 10\n"
            "while not release.exists() and time.monotonic() < deadline:\n"
            "    time.sleep(.01)\n"
            "stopped.write_text('released own synthetic descendant')\n"
        )
        wrapper = (
            "import subprocess, sys, time\n"
            f"subprocess.Popen([sys.executable, '-u', '-c', {descendant!r}, *sys.argv[1:]])\n"
            "time.sleep(10)\n"
        )

        def guard():
            if started.exists():
                raise error

        try:
            before = time.monotonic()
            with self.assertRaises(RuntimeError) as raised:
                self.run_child(wrapper, arguments=(started, release, stopped), guard=guard)
            self.assertIs(error, raised.exception)
            self.assertLess(time.monotonic() - before, 1.5)
            self.assertTrue(started.exists())
            self.assertNotEqual(self.children[0].pid, int(started.read_text()))
            self.assertFalse(stopped.exists(), "descendant must still retain inherited handles")
            self.assert_reaped()
        finally:
            # Only this fixture's descendant sees this private release path.
            # It has its own deadline too; no arbitrary process is signalled.
            release.touch()
            deadline = time.monotonic() + 2
            while started.exists() and not stopped.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            if started.exists():
                self.assertTrue(stopped.exists(), "owned fixture descendant did not acknowledge exit")

    def test_unconfirmed_child_cleanup_is_diagnosed_without_replacing_guard_error(self):
        error = RuntimeError("cancelled")
        process = unittest.mock.Mock(pid=123)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("synthetic", .2)

        def guard():
            if self.popen.called:
                raise error

        self.popen.side_effect = None
        self.popen.return_value = process
        with self.assertRaises(RuntimeError) as raised:
            self.run_child("never launched", guard=guard)
        self.assertIs(error, raised.exception)
        process.terminate.assert_called_once()
        process.kill.assert_called_once()
        self.assertEqual(2, process.wait.call_count)
        self.assertTrue(all(call.kwargs["timeout"] <= .2 for call in process.wait.call_args_list))
        self.assertIn("not confirmed reaped", " ".join(raised.exception.__notes__))
        for capture in (self.popen.call_args.kwargs["stdout"],
                        self.popen.call_args.kwargs["stderr"]):
            self.assertTrue(capture.closed)

    def test_missing_executable_and_nonzero_exit_keep_media_error_codes(self):
        with self.assertRaises(media.MediaError) as raised:
            media._run_process(
                [str(self.root / "no-such-executable")], timeout_seconds=1,
                failure_code="PROBE_FAILED", guard=lambda: None,
            )
        self.assertEqual("TOOL_NOT_FOUND", raised.exception.code)
        with self.assertRaises(media.MediaError) as raised:
            self.run_child("import sys; sys.stderr.write('encoder failed'); sys.exit(7)")
        self.assertEqual("TRANSCODE_FAILED", raised.exception.code)
        self.assertIn("exit 7", str(raised.exception))
        self.assertIn("encoder failed", str(raised.exception))
        self.assert_reaped()


if __name__ == "__main__":
    unittest.main()

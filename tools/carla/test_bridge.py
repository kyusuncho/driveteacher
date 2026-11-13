"""docker compose run --rm --no-deps -T client python -m unittest test_bridge  (the client image has carla; no server)"""
import json
import socket
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import bridge


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class GuidanceResendTest(unittest.TestCase):
    """The simulator channel (③) as the app sees it: one connected reader, lines with their receipt times."""

    def setUp(self) -> None:
        port = free_port()
        self.sim = bridge.SimChannel(port, dry_run=False, log=bridge.SendLog(None))
        self.app = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.addCleanup(self.app.close)
        self.start = time.monotonic()
        self.buffer = b""

    def read_until(self, seconds: float) -> list[tuple[float, str]]:
        """Lines received until [seconds] after setUp, with when (s after setUp)."""
        lines = []
        while (left := self.start + seconds - time.monotonic()) > 0:
            self.app.settimeout(left)
            try:
                chunk = self.app.recv(4096)
            except socket.timeout:
                break
            self.buffer += chunk
            while b"\n" in self.buffer:
                line, self.buffer = self.buffer.split(b"\n", 1)
                lines.append((time.monotonic() - self.start, line.decode()))
        return lines

    def test_guidance_is_resent_while_the_tick_loop_sends_none(self) -> None:
        self.sim.send("limit", 50)
        self.sim.send("nextturn", "RIGHT", "40.0")
        lines = self.read_until(4.0)
        turns = [t for t, line in lines if line == "nextturn,RIGHT,40.0"]
        # Sent at 0, then re-sent about every GUIDANCE_RESEND_S (polled each 0.3 s): ~1.5 and ~3.0 s. Only guidance.
        self.assertEqual(3, len(turns), lines)
        self.assertEqual(["limit,50"], [line for _, line in lines if not line.startswith("nextturn")])
        for a, b in zip(turns, turns[1:]):
            self.assertGreaterEqual(b - a, bridge.GUIDANCE_RESEND_S - 0.05)
            self.assertLess(b - a, bridge.GUIDANCE_RESEND_S + 0.8)  # one 0.3 s poll, plus a loaded host

    def test_a_loop_keeping_time_gets_no_duplicates(self) -> None:
        lines = []
        for k in range(4):  # the tick loop's own 1 Hz guidance, late as under load
            self.sim.send("nextturn", "LEFT", f"{90 - 10 * k:.1f}")
            lines += self.read_until(1.3 * (k + 1))
        self.assertEqual([f"nextturn,LEFT,{90 - 10 * k:.1f}" for k in range(4)], [line for _, line in lines])

    def test_nothing_is_resent_before_any_guidance(self) -> None:
        self.sim.send("belt", "true")
        self.assertEqual(["belt,true"], [line for _, line in self.read_until(2.0)])


class FakeConsole(ThreadingHTTPServer):
    """The Virtual Console's HTTP API, as far as PleosConsole uses it. [inactive]: PUTs answer 503 (a deactivated
    console); [hang]: paths whose requests never get an answer in time; [failing]: heartbeats answer 500."""

    def __init__(self) -> None:
        self.requests: list[tuple[float, str, str, int]] = []  # (monotonic, method, path, answer)
        self.inactive = False
        self.failing = False
        self.hang: set[str] = set()
        super().__init__(("127.0.0.1", 0), FakeHandler)
        threading.Thread(target=self.serve_forever, daemon=True).start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server_address[1]}"

    def seen(self, method: str, prefix: str, code: int = 200, since: float = 0.0) -> list[float]:
        return [t for t, m, p, c in self.requests if m == method and p.startswith(prefix) and c == code and t >= since]


class FakeHandler(BaseHTTPRequestHandler):
    def log_message(self, *args) -> None:
        pass

    def _answer(self) -> None:
        console: FakeConsole = self.server
        path = self.path.split("?")[0]
        if path in console.hang:
            time.sleep(bridge.CONSOLE_TIMEOUT_S + 0.5)
            return
        if self.command == "GET":
            code, body = 200, json.dumps({"controls": {"emulator_active": True}}).encode()
        elif self.command == "POST":
            code, body = (500 if console.failing else 200), b"{}"
        else:
            code, body = (503 if console.inactive else 200), b'{"error":"emulator_inactive"}' if console.inactive else b"{}"
        console.requests.append((time.monotonic(), self.command, self.path, code))
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = _answer


class BlinkerTest(unittest.TestCase):
    """The console turns the blinker off when it deactivates; the app takes that Off at its next speed sample, so the
    bridge keeps the console active and undoes a reset as soon as the console takes writes again."""

    def setUp(self) -> None:
        self.fake = FakeConsole()  # left running: this console's threads keep talking to it until the tests end
        self.console = bridge.PleosConsole(self.fake.url, dry_run=False, log=bridge.SendLog(None))
        self.addCleanup(setattr, self.console, "turn_wanted", None)  # its blinker thread goes quiet

    def wait_for(self, check, seconds: float = 2.0) -> float:
        start = time.monotonic()
        while not check():
            self.assertLess(time.monotonic() - start, seconds, "timed out")
            time.sleep(0.01)
        return time.monotonic() - start

    def test_the_blinker_is_written_while_the_tick_loop_is_stuck_in_a_write(self) -> None:
        self.fake.hang.add("/vehicle/controls")
        stuck = threading.Thread(target=self.console.set_controls, args=(30, 0.3, 0, 0), daemon=True)
        stuck.start()
        time.sleep(0.2)
        self.assertFalse(self.console.set_turn("BlinkRight"))
        self.wait_for(lambda: self.console.set_turn("BlinkRight"), seconds=0.5)
        self.assertTrue(stuck.is_alive())  # the controls write is still waiting for its answer

    def test_a_reset_is_put_right_well_within_a_second_of_the_console_taking_writes_again(self) -> None:
        self.console.set_turn("BlinkLeft")
        self.wait_for(lambda: self.console.set_turn("BlinkLeft"))
        self.fake.inactive = True  # deactivated: the console has turned the blinker off and answers 503
        self.console.maybe_reset()
        time.sleep(0.5)
        back = time.monotonic()
        self.fake.inactive = False
        took = self.wait_for(lambda: self.fake.seen("PUT", "/vehicle/turn-signal?mode=BlinkLeft", since=back))
        self.assertLess(took, 0.3)

    def test_the_heartbeat_tries_again_at_once_after_a_failure(self) -> None:
        self.console.start_heartbeat()
        self.fake.failing = True
        self.wait_for(lambda: self.fake.seen("POST", "/browser/session", code=500), seconds=1.0)
        time.sleep(0.45)
        failed = self.fake.seen("POST", "/browser/session", code=500)
        self.assertGreaterEqual(len(failed), 3, failed)  # every HEARTBEAT_RETRY_S, not every HEARTBEAT_S
        self.fake.failing = False
        start = time.monotonic()
        time.sleep(1.2)
        beats = self.fake.seen("POST", "/browser/session", since=start)
        self.assertIn(len(beats), (2, 3), beats)  # then every HEARTBEAT_S


if __name__ == "__main__":
    unittest.main()

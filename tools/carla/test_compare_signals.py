"""python3 -m unittest test_compare_signals   (from tools/carla)"""
import unittest

from compare_signals import analyze, clock_offset, failures, shifted

SKEW_MS, LAG_MS = -760, 80  # app clock behind the bridge's, plus delivery delay
RECEIPT_MS = 5  # from speed's HAL time to the app's callback
TICKS = 300  # 30 s at 10 Hz
AT = SKEW_MS + LAG_MS  # add to a bridge time to get when the app saw it, on the app clock


def drive():
    """Bridge: Drive, then speed (triangle 0..50 km/h every 10 s), brake 0 and steering every 100 ms, like bridge.py.
    App: the same values LAG_MS later on a clock SKEW_MS off, in its own units, plus initial reads before the bridge."""
    sent = {"gear": [(0, ["Drive"])], "turn": [(0, ["Off"])], "speed": [], "brake": [], "steering": []}
    app = {kind: [(-50 + SKEW_MS, [v])] for kind, v in
           [("gear", "PARK"), ("brake", "false"), ("turn", "OFF"), ("belt", "false"), ("steering", "0.0")]}
    app["gear"].append((AT, ["DRIVE"]))
    app["speed"] = []
    for i in range(TICKS):
        t, kph, deg = i * 100, 50 - abs(50 - i % 100), (i // 10) * 15
        sent["speed"].append((t, [str(kph)]))
        sent["brake"].append((t, ["0"]))
        sent["steering"].append((t, [str(deg)]))
        app["speed"].append((t + AT, [str(kph / 3.6), str(RECEIPT_MS)]))
        if i % 10 == 0:  # the SDK reports steering on change only
            app["steering"].append((t + AT, [str(-deg)]))
    return sent, app


def check(sent, app):
    offset, _ = clock_offset(sent["speed"], app["speed"])
    results, _ = analyze(sent, shifted(app, -offset), -200)
    hard, timed = failures(results)
    return offset, hard + timed


def set_brake(sent, app, presses, app_presses=None):
    """presses: [(from_ms, to_ms)] the bridge holds the brake at 40 %; the app reports app_presses (default: same)."""
    sent["brake"] = [(t, ["40" if any(a <= t < b for a, b in presses) else "0"]) for t, _ in sent["brake"]]
    for a, b in app_presses if app_presses is not None else presses:
        app["brake"] += [(a + AT, ["true"]), (b + AT, ["false"])]


class CompareSignalsTest(unittest.TestCase):
    def test_matching_logs_pass_and_clock_offset_is_found(self):
        sent, app = drive()
        set_brake(sent, app, [(3000, 6000), (12000, 15000)])
        offset, problems = check(sent, app)
        self.assertAlmostEqual(offset, AT, delta=20)
        self.assertEqual(problems, [])

    def test_wrong_steering_sign_fails(self):
        sent, app = drive()
        app["steering"] = [(t, [v[0].lstrip("-")]) for t, v in app["steering"]]
        self.assertTrue(any(p.startswith("steering: only") for p in check(sent, app)[1]))

    def test_dead_steering_listener_fails(self):
        sent, app = drive()
        app["steering"] = app["steering"][:1]  # only the initial read
        self.assertTrue(any(p.startswith("steering: the app showed only") for p in check(sent, app)[1]))

    def test_lost_gear_change_fails(self):
        sent, app = drive()
        app["gear"] = app["gear"][:1]
        problems = check(sent, app)[1]
        self.assertTrue(any(p.startswith("gear: the app showed only 0/1") for p in problems), problems)

    def test_inverted_brake_fails(self):
        sent, app = drive()
        presses = [(3000, 6000), (12000, 15000)]
        set_brake(sent, app, presses, app_presses=[])
        app["brake"] = app["brake"][:1] + [(a + AT, ["false" if j % 2 else "true"]) for j, a in
                                           enumerate([0, 3000, 6000, 12000, 15000])]
        self.assertTrue(any(p.startswith("brake: only") and "agree" in p for p in check(sent, app)[1]))

    def test_blinker_dropped_early_fails(self):
        sent, app = drive()
        sent["turn"] += [(2000, ["BlinkRight"]), (9000, ["Off"])]
        app["turn"] += [(2000 + AT, ["RIGHT"]), (3000 + AT, ["OFF"])]
        self.assertTrue(any(p.startswith("turn: only") for p in check(sent, app)[1]))

    def test_late_values_right_after_an_emulator_stall_are_left_out(self):
        sent, app = drive()
        app["speed"] = [row for row in app["speed"] if not 100 * 100 + AT < row[0] < 115 * 100 + AT]  # 1.5 s frozen
        app["steering"].append((11600 + AT, ["-150.0"]))  # what the bridge sent at 10 s, delivered late
        app["brake"] += [(11700 + AT, ["true"]), (11800 + AT, ["false"])]  # a press during the freeze, late
        sent["brake"] = [(t, ["40" if 10000 <= t < 10300 else v[0]]) for t, v in sent["brake"]]
        self.assertEqual(check(sent, app)[1], [])

    def test_late_values_while_the_app_stalls_are_left_out(self):
        sent, app = drive()
        # The app stalls for 2 s: speed keeps its HAL times but is received only when it resumes, and steering's
        # callbacks then bring what the bridge sent during the stall (seen in the 10/28 14:32 run).
        resume = 12000 + AT
        app["speed"] = [(t, [v[0], str(resume - t)]) if resume - 2000 <= t < resume else (t, v)
                        for t, v in app["speed"]]
        app["steering"] = [row for row in app["steering"] if not resume - 2000 <= row[0] <= resume]
        app["steering"] += [(resume + 10 * k, [f"-{deg}.0"]) for k, deg in enumerate([150, 165, 180], 1)]
        self.assertEqual(check(sent, app)[1], [])

    def test_unanswered_write_may_explain_an_app_value(self):
        sent, app = drive()
        app["brake"] += [(5000 + AT, ["true"]), (5150 + AT, ["false"])]
        sent["brake?"] = [(5000, ["40"])]  # timed out, but the console applied it before the next 10 Hz write
        self.assertEqual(check(sent, app)[1], [])
        del sent["brake?"]
        self.assertTrue(any(p.startswith("brake: only 1/2 app values") for p in check(sent, app)[1]))

    def test_lost_brake_tap_in_a_quick_series_fails(self):
        sent, app = drive()
        taps = [(4000, 4230), (4460, 4690), (4920, 5150)]  # pressed 230 ms, released 230 ms
        set_brake(sent, app, taps, app_presses=taps[:1] + taps[2:])  # the app lost the middle tap
        self.assertTrue(any(p.startswith("brake: the app showed only") for p in check(sent, app)[1]))

    def test_unanswered_write_does_not_rule_out_the_value_before_it(self):
        sent, app = drive()
        sent["turn"] += [(2000, ["BlinkLeft"]), (7000, ["BlinkLeft"]), (12000, ["Off"])]
        sent["turn?"] = [(5000, ["Off"]), (5100, ["BlinkLeft"])]  # a possible reset, then a re-send that timed out
        app["turn"] += [(2000 + AT, ["LEFT"]), (5050 + AT, ["OFF"]), (7000 + AT, ["LEFT"]), (12000 + AT, ["OFF"])]
        self.assertEqual(check(sent, app)[1], [])

    def test_speed_listener_dying_mid_drive_fails(self):
        sent, app = drive()
        app["speed"] = [row for row in app["speed"] if row[0] < 18000 + AT]
        self.assertTrue(any("in a row" in p for p in check(sent, app)[1]))

    def test_speed_that_is_not_10_hz_fails(self):
        sent, app = drive()
        app["speed"] = app["speed"][::10]
        self.assertTrue(any(p.startswith("speed: app samples every") for p in check(sent, app)[1]))

    def test_console_to_vhal_lag_is_left_out(self):
        sent, app = drive()
        # 12-15 s: the VHAL keeps its 10 Hz times but reports what the bridge sent 1.5 s earlier.
        lagged = {t + AT: v for t, v in sent["speed"]}
        app["speed"] = [(t, [str(float(lagged[t - 1500][0]) / 3.6)] + v[1:] if 12000 + AT <= t < 15000 + AT else v)
                        for t, v in app["speed"]]
        app["steering"] = [(t, [str(-float(sent["steering"][(t - AT) // 100 - 15][1][0]))]
                            if 12000 + AT <= t < 15000 + AT else v) for t, v in app["steering"]]
        self.assertEqual(check(sent, app)[1], [])

    def test_speed_in_wrong_unit_fails(self):
        sent, app = drive()
        app["speed"] = [(t, [str(float(v[0]) * 3.6)] + v[1:]) for t, v in app["speed"]]  # km/h reported as m/s
        self.assertTrue(any(p.startswith("speed: only") for p in check(sent, app)[1]))

    def test_missing_initial_read_fails(self):
        sent, app = drive()
        del app["belt"]
        self.assertIn("belt: no app sample before the bridge started or within 10000 ms of recording start", check(sent, app)[1])


if __name__ == "__main__":
    unittest.main()

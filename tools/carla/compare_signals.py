"""Stage-1 check: did the app record the values the CARLA bridge sent?

  python3 compare_signals.py signals/bridge.csv signals/app.csv

bridge.csv  `wall_ms,kind,value[,value2]` from `bridge.py --log` (Mac clock, time the write started). `kind?` rows are
            writes that got no answer (the console may or may not hold that value) and possible console resets.
            belt, limit, cameras and nextturn went over the simulator channel (③) instead, in the app's own format.
app.csv     `# wall_ms=<ms> t_ms=<ms>` anchor, then `t_ms,kind,value[,value2]` from SignalRecordTest
            (emulator elapsedRealtime: HAL time for speed and gear, callback time for the rest). Speed's value2 is
            the ms from its HAL time to the app's callback (missing in recordings before 10/28 15:00).

The emulator's wall clock is not the Mac's (0.76 s behind on 10/28), so the app timeline is first shifted onto the
bridge's by the median gap between matching speed changes. CARLA starves the emulator at times: while it is frozen
the app gets nothing, then late values. Or the console -> VHAL path lags while everything keeps ticking: the app's
speed then matches only bridge values from more than WINDOW_MS (but at most MAX_LAG_MS) ago. Those stretches (a gap
in the 10 Hz speed or such lagging speed, plus RECOVERY_MS after) are left out for every signal and reported; speed
is the canary because every console signal takes the same path. Its HAL times keep ticking while the app itself
stalls, and the callback times of the other signals then come late, so a gap in when the app received speed is left
out the same way. A wrong unit never matches at any lag. Console
outages (no answered write for STALL_MS) are left out the same way: around them writes answered 503 were seen to
take effect anyway and values to land seconds late, so the bridge log no longer says what the console holds. Per signal, with bridge values mapped to what the app should report:
  explained  each app value was a value the console may have held within WINDOW_MS (wrong unit, sign, mapping)
  agree      on-change signals (gear/brake/turn, and ③'s belt/limit/cameras/nextturn): share of the drive the app's current value matches the console's (inverted, stuck or
             early-reverted on-change signals, which report right at the bridge's changes and so pass `explained`)
  coverage   each value the bridge changed to shows up in the app, in order, before the bridge changed it again
             (lost updates; also fails on MAX_UNSHOWN_RUN misses in a row: a listener that died); delay is informational
  first      the app reported the signal before the bridge's first write, or within FIRST_MS of recording start
             (initial read; check_signals.sh starts the bridge only after the app has every first value)
Exits 1 when a check fails, 2 when the emulator or console was out too much of the drive to judge.
"""
from __future__ import annotations

import math
import sys
from bisect import bisect_left, bisect_right
from statistics import median
from typing import Callable, Dict, List, NamedTuple, Optional, Tuple

Row = Tuple[int, List[str]]  # (time ms, values)
Series = Dict[str, List[Row]]
Span = Tuple[int, int]

WINDOW_MS = 1000  # slack for clock alignment and delivery lag
STALL_MS = 1000  # a gap this long in the app's 10 Hz speed = the emulator was frozen
RECOVERY_MS = 2000  # late deliveries keep arriving this long after a freeze
MAX_LAG_MS = 5000  # console -> VHAL lag seen under heavy load: 1.1-1.9 s for 3 s at a time
GRID_MS = 100
FIRST_MS = 10_000  # SDK service binds and per-signal reads take seconds; the point is not waiting for a change
# How far back an app value may reach for a matching bridge value. GPS goes emulator console -> GNSS -> fused
# service and lagged 15 s after a freeze; a wide window still catches wrong mappings (swapped or shifted lat/lon).
LOOKBACK_MS = {"loc": 30_000}
MAX_SKEW_MS = 5000
MIN_ALIGNED = 0.5  # share of bridge speed changes the clock alignment must match
MAX_SPEED_INTERVAL_MS = 150  # median spacing of app speed samples: it must come at about 10 Hz
EXPLAINED_MIN = 0.99
AGREE_MIN = 0.98
COVERAGE_MIN = 0.95  # DISCRETE signals: held values
CONTINUOUS_COVERAGE_MIN = 0.5  # speed, steering, loc change faster than the app samples them
MAX_UNSHOWN_RUN = 5  # bridge changes in a row the app never showed (a real run had at most 2)
MAX_STALLED = 0.5
MAX_VALUES = 6
UNSURE = "?"
TURN = {"Off": "OFF", "BlinkLeft": "LEFT", "BlinkRight": "RIGHT"}

# kind -> what the app should report for this bridge value
EXPECT: Dict[str, Callable[[List[str]], List[object]]] = {
    "speed": lambda v: [float(v[0]) / 3.6],  # integer km/h -> m/s
    "gear": lambda v: [v[0].upper()],  # Drive -> DRIVE
    "brake": lambda v: ["true" if int(v[0]) > 0 else "false"],  # percent -> pressed
    "steering": lambda v: [-float(v[0])],  # the SDK reports -(console value)
    "turn": lambda v: [TURN[v[0]]],
    "loc": lambda v: [float(x) for x in v],
    # Simulator channel (③): the same line the app records, taken in place of the car's value.
    "belt": lambda v: [v[0]],
    "limit": lambda v: [v[0]],
    "cameras": lambda v: [v[0]],
    "nextturn": lambda v: list(v),
}
TOLERANCE = {"speed": 0.05 / 3.6, "steering": 0.5, "loc": 2e-6}
DISCRETE = {"gear", "brake", "turn", "belt", "limit", "cameras", "nextturn"}
SIM = "sim"  # bridge.py logs this once when it opens the simulator channel (③); not a signal
# Signals the app must report right after start (10 Hz speed, or a getter read for the on-change ones).
FIRST_CHECKED = {"speed", "gear", "brake", "turn", "steering", "belt"}


class Result(NamedTuple):
    app_n: int
    explained: Optional[Tuple[int, int]]  # app values matching the console / app values judged
    agree: Optional[Tuple[int, int]]  # grid points where the app's value matched / judged (DISCRETE only)
    coverage: Optional[Tuple[int, int]]  # bridge changes the app showed / bridge changes judged
    unshown_run: int  # longest run of bridge changes in a row the app never showed
    delay_ms: Optional[Tuple[int, int]]  # median / max delay of the changes the app showed
    first_ms: Optional[int]  # first app sample after recording start
    started: bool  # that first sample came before the bridge's first write or within FIRST_MS
    interval_ms: Optional[float]  # median spacing of app samples while the bridge sent this signal
    app_values: List[str]  # first few distinct app values


class Run(NamedTuple):
    start_ms: int  # recording start, bridge clock
    span: Span  # first and last bridge write
    stalls: List[Span]  # emulator freezes or lag (plus recovery): left out
    app_stalls: List[Span]  # gaps in when the app received speed (plus recovery): left out
    outages: List[Span]  # no answered console write (plus recovery): left out

    def stalled(self, t: float) -> bool:
        return any(a <= t <= b for a, b in self.stalls + self.app_stalls + self.outages)


class Timeline(NamedTuple):
    """What the bridge wrote for one signal, in app terms."""
    sure: List[Row]
    unsure: List[Row]

    def first_ms(self) -> int:
        return min(rows[0][0] for rows in (self.sure, self.unsure) if rows)

    def values(self, lo: float, hi: float) -> List[List[object]]:
        """Values the console may have held at some moment in [lo, hi]. An unanswered write (or possible reset)
        adds a possibility until the next answered write; it never rules out the value before it."""
        sure_t = [t for t, _ in self.sure]
        unsure_t = [t for t, _ in self.unsure]
        i = bisect_right(sure_t, lo)
        since = sure_t[i - 1] if i else -math.inf
        extra = self.unsure[bisect_right(unsure_t, since) : bisect_right(unsure_t, hi)]
        return [v for _, v in held(self.sure, sure_t, lo, hi) + extra]


def read(path: str) -> Tuple[Optional[Tuple[int, int]], Series]:
    """-> (anchor (wall_ms, t_ms) if the file has one, rows per kind in file order)."""
    anchor, series = None, {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line.startswith("#"):
                fields = dict(item.split("=", 1) for item in line[1:].split())
                anchor = (int(fields["wall_ms"]), int(fields["t_ms"]))
            elif line:
                t, kind, *values = line.split(",")
                series.setdefault(kind, []).append((int(t), values))
    return anchor, series


def shifted(series: Series, by_ms: int) -> Series:
    return {kind: [(t + by_ms, v) for t, v in rows] for kind, rows in series.items()}


def changes(rows: List[Row]) -> List[Row]:
    """Rows whose value differs from the previous row (the first row counts)."""
    out: List[Row] = []
    for t, v in rows:
        if not out or v != out[-1][1]:
            out.append((t, v))
    return out


def held(rows: List[Row], times: List[int], lo: float, hi: float) -> List[Row]:
    """Rows in effect at some moment in [lo, hi]: the last one at or before lo, then any up to hi."""
    i = bisect_right(times, lo)
    return rows[max(i - 1, 0) : bisect_right(times, hi)]


def gaps(times: List[int], longer_than: int) -> List[Span]:
    return [(a, b) for a, b in zip(times, times[1:]) if b - a > longer_than]


def same(kind: str, app: List[str], expected: List[object]) -> bool:
    tolerance = TOLERANCE.get(kind)
    if tolerance is None:
        return app == expected
    return len(app) == len(expected) and all(abs(float(a) - e) < tolerance for a, e in zip(app, expected))


def clock_offset(sent_speed: List[Row], app_speed: List[Row]) -> Tuple[int, int]:
    """-> (median app-minus-bridge time between a bridge speed change and the app's nearest change to the same
    km/h, number of changes matched). The second pass searches around the first estimate."""
    app_at: Dict[str, List[int]] = {}
    for t, v in changes([(t, [str(round(float(v[0]) * 3.6))]) for t, v in app_speed]):
        app_at.setdefault(v[0], []).append(t)
    guess, gaps_ms = 0, []
    for _ in range(2):
        gaps_ms = []
        for t, v in changes(sent_speed):
            times = app_at.get(v[0], [])
            i = bisect_left(times, t + guess)
            near = [x for x in times[max(i - 1, 0) : i + 1] if abs(x - t - guess) <= MAX_SKEW_MS]
            if near:
                gaps_ms.append(min(near, key=lambda x: abs(x - t - guess)) - t)
        if not gaps_ms:
            raise SystemExit("no matching speed changes: did the bridge drive, and did the app record speed?")
        guess = round(median(gaps_ms))
    return guess, len(gaps_ms)


def explained(kind: str, line: Timeline, got: List[Row], run: Run) -> Tuple[int, int]:
    hits = judged = 0
    for t, v in got:
        if t - WINDOW_MS < line.first_ms() or run.stalled(t):  # before the bridge's first write the state is unknown
            continue
        judged += 1
        hits += any(same(kind, v, e) for e in line.values(t - LOOKBACK_MS.get(kind, WINDOW_MS), t + WINDOW_MS))
    return hits, judged


def agreement(kind: str, line: Timeline, got: List[Row], run: Run) -> Tuple[int, int]:
    got_t = [t for t, _ in got]
    hits = judged = 0
    t = line.first_ms() + WINDOW_MS
    while t <= run.span[1]:
        i = bisect_right(got_t, t)
        if i and not run.stalled(t):
            judged += 1
            hits += any(same(kind, got[i - 1][1], e) for e in line.values(t - WINDOW_MS, t + WINDOW_MS))
        t += GRID_MS
    return hits, judged


def coverage(kind: str, line: Timeline, got: List[Row], run: Run) -> Tuple[Tuple[int, int], int, List[int]]:
    """-> ((changes shown, changes judged), longest run of unshown changes, delays).
    For DISCRETE signals each change must be shown by a later app row than the previous change (a lost brake tap is
    not covered by its neighbour). Not for speed/steering/loc: their values recur within the window, so chaining
    would latch onto a later recurrence and skip real matches. The bridge's first change may be shown by the app's
    value from before the bridge started, and an unanswered write of the same value since the previous change may
    have taken effect early."""
    got_t = [t for t, _ in got]
    changed = changes(line.sure)
    shown = judged = run_len = longest = 0
    delays = []
    after, previous = -1, -math.inf
    for i, (t, e) in enumerate(changed):
        early = [u for u, v in line.unsure if previous < u < t and same(kind, [str(x) for x in v], e)]
        previous = t
        if run.stalled(t):
            continue
        judged += 1
        lo = min([t] + early) - WINDOW_MS
        hi = (changed[i + 1][0] if i + 1 < len(changed) else math.inf) + WINDOW_MS
        if i == 0:
            start = max(bisect_right(got_t, lo) - 1, 0)
        else:
            start = max(bisect_left(got_t, lo), after + 1 if kind in DISCRETE else 0)
        j = next((k for k in range(start, bisect_right(got_t, hi)) if same(kind, got[k][1], e)), None)
        if j is None:
            run_len += 1
            longest = max(longest, run_len)
            continue
        run_len, after = 0, j
        shown += 1
        delays.append(max(got_t[j], t) - t)
    return (shown, judged), longest, delays


def result(kind: str, sent: List[Row], unsure: List[Row], got: List[Row], run: Run) -> Result:
    first = next((t - run.start_ms for t, _ in got if t >= run.start_ms), None)
    started = first is not None and (first <= FIRST_MS or run.start_ms + first <= run.span[0])
    values = list(dict.fromkeys(",".join(v) for _, v in got))[:MAX_VALUES]
    if kind not in EXPECT or not sent:
        return Result(len(got), None, None, None, 0, None, first, started, None, values)
    to_app = EXPECT[kind]
    line = Timeline([(t, to_app(v)) for t, v in sent], [(t, to_app(v)) for t, v in unsure])
    covered, unshown_run, delays = coverage(kind, line, got, run)
    agree = agreement(kind, line, got, run) if kind in DISCRETE else None
    delay = (round(median(delays)), max(delays)) if delays else None
    during = [t for t, _ in got if sent[0][0] <= t <= sent[-1][0]]
    interval = median(b - a for a, b in zip(during, during[1:])) if len(during) > 1 else None
    return Result(len(got), explained(kind, line, got, run), agree, covered, unshown_run, delay, first, started,
                  interval, values)


def lagging(sent: Series, app_speed: List[Row]) -> List[Span]:
    """Where the app's speed shows only bridge values from more than WINDOW_MS (at most MAX_LAG_MS) ago."""
    to_app = EXPECT["speed"]
    line = Timeline([(t, to_app(v)) for t, v in sent.get("speed", [])],
                    [(t, to_app(v)) for t, v in sent.get("speed" + UNSURE, [])])
    spans = []
    for t, v in app_speed:
        if t - WINDOW_MS < line.first_ms() or any(same("speed", v, e) for e in line.values(t - WINDOW_MS, t + WINDOW_MS)):
            continue
        if any(same("speed", v, e) for e in line.values(t - MAX_LAG_MS, t + WINDOW_MS)):
            spans.append((t - WINDOW_MS, t + RECOVERY_MS))
    return merged(spans)


def analyze(sent: Series, app: Series, start_ms: int) -> Tuple[Dict[str, Result], Run]:
    """[app] must already be on the bridge clock; [start_ms] is the recording start on that clock."""
    app = {k: sorted(rows, key=lambda row: row[0]) for k, rows in app.items()}
    sent = {k: sorted(rows, key=lambda row: row[0]) for k, rows in sent.items()}
    writes = [t for rows in sent.values() for t, _ in rows]
    received = sorted(t + int(v[1]) for t, v in app.get("speed", []) if len(v) > 1)  # none in old recordings
    app = {**app, "speed": [(t, v[:1]) for t, v in app.get("speed", [])]}  # the receipt delay is not a value
    app_speed = app["speed"]
    stalls = [(a - WINDOW_MS, b + RECOVERY_MS) for a, b in gaps([t for t, _ in app_speed], STALL_MS)]
    stalls += lagging(sent, app_speed) if sent.get("speed") else []
    app_stalls = [(a - WINDOW_MS, b + RECOVERY_MS) for a, b in gaps(received, STALL_MS)]
    outages = [(a - WINDOW_MS, b + RECOVERY_MS) for a, b in gaps([t for t, _ in sent.get("speed", [])], STALL_MS)]
    run = Run(start_ms, (min(writes), max(writes)), stalls, app_stalls, outages)
    kinds = sorted(FIRST_CHECKED | {k.rstrip(UNSURE) for k in sent} | set(app))
    return {k: result(k, sent.get(k, []), sent.get(k + UNSURE, []), app.get(k, []), run) for k in kinds}, run


def share(spans: List[Span], run: Run) -> float:
    lo, hi = run.span
    covered = sum(max(0, min(b, hi) - max(a, lo)) for a, b in merged(spans))
    return covered / (hi - lo) if hi > lo else 0.0


def merged(spans: List[Span]) -> List[Span]:
    out: List[Span] = []
    for a, b in sorted(spans):
        if out and a <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], b))
        else:
            out.append((a, b))
    return out


def failures(results: Dict[str, Result]) -> Tuple[List[str], List[str]]:
    """-> (app defects whatever the emulator did, failures of the timing-based checks). The first kind also keeps
    an app defect from passing as emulator freezes: those are found from gaps in the app's 10 Hz speed."""
    hard, out = [], []
    for kind, r in results.items():
        if kind in FIRST_CHECKED and not r.started:
            hard.append(f"{kind}: no app sample before the bridge started or within {FIRST_MS} ms of recording start")
        if kind == "speed" and r.interval_ms is not None and r.interval_ms > MAX_SPEED_INTERVAL_MS:
            hard.append(f"speed: app samples every {r.interval_ms:.0f} ms (median), not about 10 Hz")
        if r.explained is None:
            continue
        if r.app_n == 0:
            hard.append(f"{kind}: the bridge sent it, the app recorded nothing")
            continue
        for name, pair, floor in [("app values match", r.explained, EXPLAINED_MIN),
                                  ("of the time agree", r.agree, AGREE_MIN)]:
            if pair and pair[1] and pair[0] / pair[1] < floor:
                out.append(f"{kind}: only {pair[0]}/{pair[1]} {name} with what the bridge sent")
        shown, changed = r.coverage
        floor = COVERAGE_MIN if kind in DISCRETE else CONTINUOUS_COVERAGE_MIN
        if changed and shown / changed < floor:
            out.append(f"{kind}: the app showed only {shown}/{changed} bridge changes")
        if r.unshown_run > MAX_UNSHOWN_RUN:  # also catches a listener that only gave its initial value
            out.append(f"{kind}: the app missed {r.unshown_run} bridge changes in a row (listener stopped?)")
    return hard, out


def unconnected(offered: bool, sent: Series) -> List[str]:
    """The bridge sends belt on ③ as soon as the app connects: none in its log means the app never did, and then
    belt, limit and cameras would go unchecked rather than fail."""
    if offered and "belt" not in sent:
        return ["belt: the bridge opened the simulator channel (③), the app never connected (adb reverse? debug build?)"]
    return []


def ratio(pair: Optional[Tuple[int, int]]) -> str:
    if pair is None:
        return "-"
    return f"{pair[0]}/{pair[1]} {100 * pair[0] / pair[1]:.1f}%" if pair[1] else "0/0"


def report(results: Dict[str, Result]) -> None:
    print(f"{'signal':9s}{'app n':>7s}{'every':>8s}  {'explained':20s}{'agree':20s}{'coverage':18s}"
          f"{'delay med/max':>15s}{'first':>7s}  app values")
    for kind, r in results.items():
        every = f"{r.interval_ms:.0f}ms" if r.interval_ms is not None else "-"
        delay = f"{r.delay_ms[0]}/{r.delay_ms[1]} ms" if r.delay_ms else "-"
        first = f"{r.first_ms / 1000:.1f}s" if r.first_ms is not None else "-"
        note = "" if kind in EXPECT else "  (the bridge cannot set this on the emulator)"
        print(f"{kind:9s}{r.app_n:7d}{every:>8s}  {ratio(r.explained):20s}{ratio(r.agree):20s}{ratio(r.coverage):18s}"
              f"{delay:>15s}{first:>7s}  {' '.join(r.app_values)}{note}")


def main(bridge_path: str, app_path: str) -> int:
    _, sent = read(bridge_path)
    offered = sent.pop(SIM, None) is not None
    anchor, app = read(app_path)
    if anchor is None:
        raise SystemExit(f"{app_path}: missing the '# wall_ms=.. t_ms=..' anchor line")
    wall_ms, t_ms = anchor
    app_wall = shifted(app, wall_ms - t_ms)
    offset, matched = clock_offset(sent.get("speed", []), app_wall.get("speed", []))
    speed_changes = len(changes(sent.get("speed", [])))
    print(f"app clock {offset:+d} ms vs the bridge (matched {matched}/{speed_changes} speed changes), corrected below")
    if matched < MIN_ALIGNED * speed_changes:
        print(f"FAIL could not line up the clocks (the emulator clock may be more than {MAX_SKEW_MS} ms off)")
        return 1
    results, run = analyze(sent, shifted(app_wall, -offset), wall_ms - offset)
    unanswered = sum(len(rows) for k, rows in sent.items() if k.endswith(UNSURE))
    out = share(run.stalls + run.app_stalls + run.outages, run)
    print(f"left out: emulator freezes or lag {len(merged(run.stalls))} ({100 * share(run.stalls, run):.0f}% of the "
          f"drive), app stalls {len(run.app_stalls)} ({100 * share(run.app_stalls, run):.0f}%), console outages "
          f"{len(run.outages)} ({100 * share(run.outages, run):.0f}%), together {100 * out:.0f}%; "
          f"{unanswered} unanswered writes or possible resets")
    report(results)
    hard, timed = failures(results)
    hard += unconnected(offered, sent)
    if out > MAX_STALLED and not hard:
        for p in timed:
            print(f"(not judged) {p}")
        print(f"INCONCLUSIVE: emulator or console out for {100 * out:.0f}% of the drive; free some CPU and rerun")
        return 2
    for p in hard + timed:
        print(f"FAIL {p}")
    if hard or timed:
        print(f"{len(hard) + len(timed)} check(s) failed")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))

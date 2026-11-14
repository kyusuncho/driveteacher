"""A careful scripted driver for the ego, in place of the traffic manager's autopilot (bridge.py --driver).

The autopilot pulls away and stops harder than a driving examiner allows (PLAN 2단계). This one drives like an
examinee: gentle acceleration and braking (well under 9.7 and 10.8 km/h per second), slowing for curves, stopping
before the line at red and yellow lights with the brake held, shifting to N while it waits and back to D before it
moves off, signalling left as it pulls out and blinking before turns, never over the road's limit. It keeps its lane
and takes the straightest way through junctions, except where a given route (left, right, ...) says to turn: each turn
at the first junction where its lane can make it onto a road that goes on. Or it drives a course (course.py): a planned
way through given junctions, with U-turns and lane changes, stopping where it ends. It tells the bridge the next turn the way
the car's navigation would (NaviHelper's next turn and its distance). It does not look for other cars, so the bridge
spawns none with it.
"""
import math
import time
from collections import deque
from typing import Callable, Optional

import carla

CRUISE_KPH = 40.0  # at most; less where the road's limit is lower
ACCEL = 1.2  # m/s², planned acceleration (4.3 km/h per s)
DECEL = 1.5  # m/s², planned braking (5.4 km/h per s)
MAX_DECEL = 2.2  # m/s² (7.9 km/h per s) when a light changes close by; a yellow that needs more is driven through
LATERAL = 1.5  # m/s² in curves
STOP_MARGIN_M = 4.0  # the car's centre stops this far before the stop line; its nose is ~2.4 m ahead of the centre
STEP_M = 2.0
PLAN_M = 100.0  # far enough to see past a junction it is about to signal for
# ...on a course, past the longest it has: 학여울역's U-turn runs 89 m through junctions, and is signalled from 40 m before
COURSE_PLAN_M = 200.0
# Waiting at a light: N after this long as the app counts it, in real time (the road test wants it within 10 s). A
# console stall holds the tick loop, so simulated seconds can last several real ones: on 11/14 three of them took 12.9 s
# and cost 5 points (item 4). The longest stall seen was 6.6 s.
NEUTRAL_AFTER_S = 2.0
SHIFT_S = 1.0  # a gear is selected this long before the brake comes off, and P only after this long at rest
HOLD_BRAKE = 0.6
REF_LEAD = 1.0  # m/s: the target speed stays this close to the car's, or the controller lunges to catch up
MAX_THROTTLE = 0.6
# From rest the throttle goes straight to about where CARLA's car starts to move, as a driver's foot goes from the brake
# to the accelerator. The controller winding up to it left the car standing 1-2 s with the brake already off, and the
# app allows 3 s: console stalls on top made it 3 s and cost 5 points (item 20, twice on 11/1).
LAUNCH_THROTTLE = 0.45
STOPPED_MPS = 0.5 / 3.6  # the app's standstill
MOVING_MPS = 3.0 / 3.6  # ...and where its stop ends
KP, KI = 0.4, 0.1  # throttle, per m/s of error (the integral makes up for drag)
KB = 0.3  # brake, per m/s of error: CARLA's brakes bite hard, and no integral so they never wind up
BLINK_BEFORE_M = 40.0  # before a junction it turns through (the law says 30 m); the app counts from its middle
TURN_OFF_M = 15.0  # ...and off this far past that middle (as the app counts metres) if not out of the junction yet
PULL_OUT_M = 20.0  # the left signal from the start until this far in D; the app wants it on by 10 m and off by 30 m
TURN_DEG = 30.0  # a junction it turns through more than this is a turn (more than UTURN_DEG: a U-turn, never taken)
UTURN_DEG = 150.0
ROUTE_ON_M = 200.0  # a turn is taken only onto a lane that goes on this far (a converted map has dead ends)
# A course's lane change is signalled from this far before the lane it goes into starts (course.py LANE_CHANGE_M on):
# 15 m before the car starts to move over.
CHANGE_BLINK_M = 40.0
ARRIVED_M = STOP_MARGIN_M + 3.0  # at rest this close to a course's end: arrived


LIGHTS = {"left": carla.VehicleLightState.LeftBlinker, "right": carla.VehicleLightState.RightBlinker}


def clamp(x: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, x))


def yaw_diff(a: float, b: float) -> float:
    """b − a in degrees, in (−180, 180]. CARLA's yaw grows clockwise seen from above: positive is a right turn."""
    return (b - a + 180.0) % 360.0 - 180.0


def straightest(options: list[carla.Waypoint], heading: float) -> carla.Waypoint:
    """The way on that turns least: the driver's choice at every junction."""
    return min(options, key=lambda w: abs(yaw_diff(heading, w.transform.rotation.yaw)))


def leads_on(wp: carla.Waypoint, meters: float, step: float = 2.0) -> bool:
    """Whether driving straight on from [wp] goes [meters] without the lane running out."""
    for _ in range(int(meters / step)):
        options = wp.next(step)
        if not options:
            return False
        wp = straightest(options, wp.transform.rotation.yaw)
    return True


def exit_of(wp: carla.Waypoint, step: float = 2.0, limit_m: float = 150.0) -> carla.Waypoint:
    """The first waypoint past the junction [wp] is in (wp itself outside one)."""
    for _ in range(int(limit_m / step)):
        if not wp.is_junction:
            break
        options = wp.next(step)
        if not options:
            break
        wp = straightest(options, wp.transform.rotation.yaw)
    return wp


def side(turn: float) -> Optional[str]:
    """A heading change (yaw_diff) -> "left" | "right" for a turn, None for straight on or a U-turn."""
    if TURN_DEG < abs(turn) <= UTURN_DEG:
        return "right" if turn > 0 else "left"
    return None


def signal_side(turn: float) -> Optional[str]:
    """The blinker a heading change calls for: a U-turn is made from the left in Korea (as the app reads TBT)."""
    return "left" if abs(turn) > UTURN_DEG else side(turn)


def speed(actor: carla.Actor) -> float:
    v = actor.get_velocity()
    return math.hypot(v.x, v.y)  # as bridge.py sends it


class Driver:
    def __init__(
        self, world: carla.World, ego: carla.Actor, dt: float, speed_limit: Optional[Callable[[], float]] = None,
        route: Optional[list[str]] = None, turn_signal: bool = True, course: Optional[list] = None,
    ) -> None:
        """[speed_limit]: the road's, km/h; CARLA's own by default (it reads speed signs, which only its towns have).
        [route]: the turns to make, "left" | "right", in order. [turn_signal]: False for an examinee who forgets to
        signal turns (pulling out is still signalled). [course]: course.route()'s points to drive instead, from where
        the car stands to where it stops."""
        self.ego, self.dt = ego, dt
        self.speed_limit = speed_limit or ego.get_speed_limit
        self.map = world.get_map()
        self.stops = [
            (light, [(w.transform.location.x, w.transform.location.y) for w in light.get_stop_waypoints()])
            for light in world.get_actors().filter("traffic.traffic_light")
        ]
        wheels = ego.get_physics_control().wheels
        self.max_steer_deg = wheels[0].max_steer_angle
        self.wheelbase = wheels[0].position.distance(wheels[2].position) / 100.0  # cm -> m
        self.plan = [self.map.get_waypoint(ego.get_location())]
        self.course = course
        self.course_next = 1  # the course's next point to plan
        self.marks = {id(p.wp): p for p in course or []}  # plan waypoint -> its course point
        if course:
            self.plan = [course[0].wp]
        self.dead_end = False
        self.v_ref = 0.0
        self.integral = 0.0
        self.gear = "Park"
        self.since_shift = 0.0
        self.at_rest = 0.0
        self.route = deque(route or [])  # the turns still to make
        self.turn_at: Optional[int] = None  # id of the plan's waypoint where it starts making the next of those turns
        self.turn_signal = turn_signal
        self.turn_blink = carla.VehicleLightState.NONE  # for the junction ahead or the one the car is in
        # The junction the car is in: its length, how far into it (CARLA m), where it turns ("left", "right" or None)
        # and self.driven where the car passed its middle.
        self.junction: Optional[tuple[float, float, Optional[str], Optional[float]]] = None
        self.driven = 0.0  # m in D since the start as the app counts them: pulling out of park until PULL_OUT_M
        self.wall: Optional[float] = None  # time.monotonic() at the last step
        self.t = 0.0
        self.recent: deque[tuple[float, float]] = deque()  # (t, km/h) for the 1 s rate
        self.rates = (0.0, 0.0)
        self.brake_off_at: Optional[float] = None  # wall time the brake came off in this stop (app item 20)
        self.longest_unbraked = 0.0  # wall s, as the app counts: the emulator holds through console stalls
        self.worst = ""  # where the hardest braking second ended
        self.stopping_for = None

    def step(self, phase: str) -> str:
        """Apply this tick's control for the bridge phase (park | drive | stop); returns the gear for the console."""
        self.t += self.dt
        v = speed(self.ego)
        # The emulator's car goes at the speed it is sent for as long as it is sent, which under load is longer than
        # the simulated time (CARLA ran 1.7x slower than real time on 10/29): count the distance the app sees.
        now = time.monotonic()
        since = now - self.wall if self.wall is not None else 0.0
        if self.gear == "Drive":
            self.driven += v * since
        self.wall = now
        self._update_plan()
        self._track_junction(v)
        wanted, for_light = self._allowed_speed(v) if phase == "drive" else (0.0, False)
        self.at_rest = self.at_rest + since if v < 0.1 and wanted < 0.5 else 0.0  # real seconds, as the app counts
        self._shift(phase, wanted)
        self.since_shift += self.dt
        go = self.gear == "Drive" and self.since_shift >= SHIFT_S and wanted >= 0.5
        target = wanted if go else 0.0
        fall = MAX_DECEL if for_light else DECEL  # only a light changing close by needs more than planned braking
        self.v_ref = clamp(target, self.v_ref - fall * self.dt, self.v_ref + ACCEL * self.dt)
        self.v_ref = clamp(self.v_ref, v - REF_LEAD, v + REF_LEAD)
        control = self._pedals(v, go) if phase != "park" else carla.VehicleControl(brake=1.0)
        self.ego.apply_control(control)
        # As the app judges item 20: from the brake coming off to any moment still standing, a stop lasting to 3 km/h.
        if control.brake > 0.0 or v >= MOVING_MPS or self.gear == "Park":
            self.brake_off_at = None
        elif self.brake_off_at is None:
            self.brake_off_at = now
        if self.brake_off_at is not None and v < STOPPED_MPS:
            self.longest_unbraked = max(self.longest_unbraked, now - self.brake_off_at)
        self._signal(phase)
        if phase != "park":  # the car settles onto the road after it spawns; the console ignores speed in P
            self._measure(v)
        return self.gear

    @property
    def arrived(self) -> bool:
        """At rest at the end of its course."""
        return (self.course is not None and self.dead_end and speed(self.ego) < 0.1
                and self._ahead()[-1][0] < ARRIVED_M)

    def report(self) -> str:
        up, down = self.rates
        return (f"driver: largest 1 s speed changes +{up:.1f} / {down:.1f} km/h per s (hard: +9.7 / -10.8); "
                f"hardest braking ended {self.worst}; longest standing in gear without the brake "
                f"{self.longest_unbraked:.1f} s (allowed: 3)")

    def _shift(self, phase: str, wanted: float) -> None:
        if phase == "park":
            gear = "Park"
        elif phase == "stop":
            gear = "Park" if self.at_rest >= SHIFT_S else self.gear
        elif wanted >= 0.5:
            gear = "Drive"
        else:
            gear = "Neutral" if self.at_rest >= NEUTRAL_AFTER_S else self.gear
        if gear != self.gear:
            self.gear, self.since_shift = gear, 0.0

    def _pedals(self, v: float, go: bool) -> carla.VehicleControl:
        steer = self._steer(v)
        if not go and v < 0.3:  # standing (or about to): hold the brake
            self.integral = 0.0
            return carla.VehicleControl(throttle=0.0, brake=HOLD_BRAKE, steer=steer)
        error = self.v_ref - v
        if error < -0.1:
            self.integral = 0.0
            return carla.VehicleControl(throttle=0.0, brake=min(-KB * error, 1.0), steer=steer)
        self.integral = clamp(self.integral + error * self.dt, 0.0, 2.0)
        throttle = clamp(KP * error + KI * self.integral, 0.0, MAX_THROTTLE)
        if v < STOPPED_MPS:  # pulling away
            throttle = max(throttle, LAUNCH_THROTTLE)
        return carla.VehicleControl(throttle=throttle, steer=steer)

    def _junction(self, wp: carla.Waypoint) -> bool:
        """In a junction; on a course also on road short enough between two to be part of one (course.JOIN_M)."""
        mark = self.marks.get(id(wp))
        return mark.junction if mark else wp.is_junction

    def _update_plan(self) -> None:
        here = self.ego.get_transform()
        # Lost the lane: start over. A course's plan jumps across a lane change, so it is only ever trimmed.
        if self.course is None and self.plan[0].transform.location.distance(here.location) > 6.0:
            self.plan = [self.map.get_waypoint(here.location)]
            self.turn_at = None
        forward = here.get_forward_vector()
        while len(self.plan) > 1:
            d = self.plan[0].transform.location - here.location
            if d.x * forward.x + d.y * forward.y > 0:
                break
            if self.plan.pop(0).id == self.turn_at:  # the car is making that turn: it counts as made
                self.turn_at = None
                print(f"driver t={self.t:.1f}s: turning {self.route.popleft()}")
        if self.course is not None:
            while self.course_next < len(self.course) and len(self.plan) * STEP_M < COURSE_PLAN_M:
                self.plan.append(self.course[self.course_next].wp)
                self.course_next += 1
            self.dead_end = self.course_next >= len(self.course)
            return
        self.dead_end = False
        while len(self.plan) * STEP_M < PLAN_M:
            last = self.plan[-1]
            options = last.next(STEP_M)
            if not options:  # the map's edge, or a lane a converted (OSM) map leaves unconnected at a junction
                self.dead_end = True
                break
            self.plan.append(self._choose(options, last))

    def _choose(self, options: list[carla.Waypoint], last: carla.Waypoint) -> carla.Waypoint:
        """Straight on, unless the route's next turn can be made here onto a road that goes on. One turn at a time
        is planned; it is crossed off the route once the car is in it (a plan can be started over before)."""
        heading = last.transform.rotation.yaw
        if self.route and self.turn_at is None and len(options) > 1:
            fits = [w for w in options if side(yaw_diff(heading, exit_of(w).transform.rotation.yaw)) == self.route[0]
                    and leads_on(exit_of(w), ROUTE_ON_M)]
            if fits:
                chosen = min(fits, key=lambda w: abs(abs(yaw_diff(heading, exit_of(w).transform.rotation.yaw)) - 90))
                self.turn_at = chosen.id
                return chosen
        return straightest(options, heading)

    def _track_junction(self, v: float) -> None:
        if not self._junction(self.plan[0]):
            self.junction = None
            return
        if self.junction is None:  # just entered: its length and turn from the plan through it
            ahead = self._ahead()
            out = next(((s, w) for s, w in ahead if not self._junction(w)), None)
            length = out[0] if out else ahead[-1][0]
            turn = (signal_side(yaw_diff(self.ego.get_transform().rotation.yaw, out[1].transform.rotation.yaw))
                    if out else None)
            self.junction = (length, 0.0, turn, None)
        length, into, turn, mid = self.junction
        into += v * self.dt
        if mid is None and into >= length / 2:
            mid = self.driven
        self.junction = (length, into, turn, mid)

    def next_turn(self) -> tuple[str, float]:
        """What the car's navigation would give as the next turn: ("LEFT" | "RIGHT", metres to the middle of the
        first junction ahead the plan turns through), or ("OFF", 0.0). The one the car is in counts until its
        middle; one whose end is beyond the plan is not known yet."""
        if self.junction is not None:
            length, into, turn, _ = self.junction
            if turn and into < length / 2:
                return turn.upper(), length / 2 - into
        ahead = self._ahead()
        i = next((k for k, (_, w) in enumerate(ahead) if not self._junction(w)), len(ahead))  # past the one it is in
        while i < len(ahead):
            entry = next((k for k in range(i, len(ahead)) if self._junction(ahead[k][1])), None)
            if entry is None:
                break
            out = next((k for k in range(entry, len(ahead)) if not self._junction(ahead[k][1])), None)
            if out is None:
                break
            turn = signal_side(yaw_diff(ahead[entry - 1][1].transform.rotation.yaw,
                                        ahead[out][1].transform.rotation.yaw))
            if turn:
                return turn.upper(), (ahead[entry][0] + ahead[out][0]) / 2
            i = out
        return "OFF", 0.0

    def _ahead(self) -> list[tuple[float, carla.Waypoint]]:
        """Plan waypoints with their distance along the road from the car."""
        s = self.plan[0].transform.location.distance(self.ego.get_location())
        out = [(s, self.plan[0])]
        for a, b in zip(self.plan, self.plan[1:]):
            s += a.transform.location.distance(b.transform.location)
            out.append((s, b))
        return out

    def _allowed_speed(self, v: float) -> tuple[float, bool]:
        """Fastest speed from which every limit ahead (curves, stop lines) can be met braking at DECEL, and whether
        a light says stop."""
        ahead = self._ahead()
        caps = [(0.0, min(CRUISE_KPH, self.speed_limit()) / 3.6)]
        for (s, a), (_, b) in zip(ahead, ahead[1:]):
            turn = abs(math.radians(yaw_diff(a.transform.rotation.yaw, b.transform.rotation.yaw)))
            if turn > 1e-3:
                caps.append((s, math.sqrt(LATERAL * a.transform.location.distance(b.transform.location) / turn)))
        line = self._stop_line(ahead, v)
        if line is not None:
            caps.append((line - STOP_MARGIN_M, 0.0))
        if self.dead_end:  # stop before the lane runs out: past it there is no road under the car
            caps.append((ahead[-1][0] - STOP_MARGIN_M, 0.0))
        return min(math.sqrt(cap**2 + 2 * DECEL * max(0.0, s)) for s, cap in caps), line is not None

    def _stop_line(self, ahead: list[tuple[float, carla.Waypoint]], v: float) -> float | None:
        """Distance to the first stop line ahead whose light says stop, or None. Once stopping for a light it keeps
        stopping through yellow and red, even a little past the line; a new yellow is stopped for only if it can be
        at MAX_DECEL."""
        here = self.ego.get_location()
        near = [(light, xy) for light, stops in self.stops for xy in stops
                if math.hypot(xy[0] - here.x, xy[1] - here.y) < PLAN_M + 5]
        for s, wp in ahead:
            loc = wp.transform.location
            for light, (x, y) in near:
                if math.hypot(x - loc.x, y - loc.y) > 1.5:
                    continue
                state = light.get_state()
                gap = s - STOP_MARGIN_M
                red, yellow = state == carla.TrafficLightState.Red, state == carla.TrafficLightState.Yellow
                held = self.stopping_for == light.id and (red or yellow) and s > 0.5
                stop = held or (red and gap > -1.0) or (yellow and gap > 0 and v**2 / (2 * gap) <= MAX_DECEL)
                if stop and self.stopping_for != light.id:
                    print(f"driver t={self.t:.1f}s: {state} light, line {s:.1f} m ahead, {v * 3.6:.1f} km/h")
                if stop:
                    self.stopping_for = light.id
                    return s
        self.stopping_for = None
        return None

    def _steer(self, v: float) -> float:
        """Pure pursuit on the plan."""
        here = self.ego.get_transform()
        reach = max(4.0, 0.6 * v + 3.0)
        target = next((w for w in self.plan if w.transform.location.distance(here.location) >= reach), self.plan[-1])
        d = target.transform.location - here.location
        yaw = math.radians(here.rotation.yaw)
        ahead = d.x * math.cos(yaw) + d.y * math.sin(yaw)
        right = -d.x * math.sin(yaw) + d.y * math.cos(yaw)
        angle = math.atan2(2 * self.wheelbase * math.sin(math.atan2(right, ahead)), reach)
        return clamp(math.degrees(angle) / self.max_steer_deg, -1.0, 1.0)

    def _signal(self, phase: str) -> None:
        """Blink from BLINK_BEFORE_M before a junction the plan turns through until the car is out of it, or
        TURN_OFF_M past its middle as the app counts metres (CARLA can run slower than real time, and the app then
        counts more). Pulling out of park from the road edge (road test items 11~13) comes first: left from the start
        of the drive until PULL_OUT_M, even when that ends inside a junction. On a course, a lane change is
        signalled from CHANGE_BLINK_M before the new lane until the car is in it (course.py keeps it clear of turns)."""
        if phase != "drive" or not self.turn_signal:
            self.turn_blink = carla.VehicleLightState.NONE
        elif self.junction is None:  # inside a junction, keep what was decided before it
            blink = carla.VehicleLightState.NONE
            ahead = self._ahead()
            entry = next((i for i, (s, w) in enumerate(ahead) if self._junction(w) and s < BLINK_BEFORE_M), None)
            out = next((w for _, w in ahead[entry:] if not self._junction(w)), None) if entry else None
            if out is not None:
                turn = signal_side(yaw_diff(ahead[entry - 1][1].transform.rotation.yaw, out.transform.rotation.yaw))
                if turn:
                    blink = LIGHTS[turn]
            change = next((self.marks[id(w)].change for s, w in ahead
                           if s < CHANGE_BLINK_M and id(w) in self.marks and self.marks[id(w)].change), None)
            self.turn_blink = LIGHTS[change] if change and blink == carla.VehicleLightState.NONE else blink
        elif self.junction[3] is not None and self.driven - self.junction[3] >= TURN_OFF_M:
            self.turn_blink = carla.VehicleLightState.NONE
        pulling_out = phase == "drive" and self.driven < PULL_OUT_M
        blink = carla.VehicleLightState.LeftBlinker if pulling_out else self.turn_blink
        if self.ego.get_light_state() != blink:
            self.ego.set_light_state(blink)

    def _measure(self, v: float) -> None:
        self.recent.append((self.t, v * 3.6))
        while self.t - self.recent[0][0] > 1.0 + 1e-6:
            self.recent.popleft()
        if self.t - self.recent[0][0] >= 1.0 - 1e-6:
            rate = self.recent[-1][1] - self.recent[0][1]
            if rate < self.rates[1]:
                self.worst = f"t={self.t:.1f}s at {self.recent[-1][1]:.1f} km/h"
            self.rates = (max(self.rates[0], rate), min(self.rates[1], rate))

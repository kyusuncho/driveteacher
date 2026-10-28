"""CARLA -> Pleos emulator bridge.

Spawns an autopilot ego car plus traffic in CARLA and mirrors the ego state into the Pleos emulator:
  - speed / pedals / steering / gear / turn signal -> Pleos Virtual Console HTTP API (undocumented, emulator v2.1.0)
  - position -> emulator console `geo fix` (CARLA town coordinates placed around ORIGIN_LAT/LON)

No browser tab needed; close the Virtual Console page while bridging so its own physics doesn't overwrite us.

  ./run.sh --dry-run    # CARLA only, print what would be sent
  ./run.sh              # drive the Pleos emulator (default 60 s; --seconds N)
  ./run.sh --log signals/bridge.csv   # also log every value the consoles accepted (check_signals.sh)
"""
import argparse
import http.client
import json
import math
import os
import random
import signal
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import carla

TICK_SECONDS = 0.05  # synchronous mode: the bridge advances CARLA by this much per world.tick()
SEND_EVERY_TICKS = 2  # 10 Hz to the Pleos console
GPS_EVERY_TICKS = 20  # 1 Hz
TRAFFIC_COUNT = 20
NEARBY_METERS = 50.0
EGO_BLUEPRINT = "vehicle.tesla.model3"
PREROLL_S = 4.0  # with --record: start parked in P, so the video shows the app before driving
POSTROLL_S = 8.0  # ...and end braking to a stop and back in P
# ponytail: CARLA steer is normalized [-1, 1]; assume ~1.25 turns lock-to-lock. Tune if the console looks off.
STEERING_WHEEL_MAX_DEG = 450
# CARLA town origin is placed here (Seoul City Hall). Equirectangular is fine at town scale (~1-2 km).
ORIGIN_LAT = float(os.environ.get("ORIGIN_LAT", "37.5665"))
ORIGIN_LON = float(os.environ.get("ORIGIN_LON", "126.9780"))
METERS_PER_DEG_LAT = 111_320.0
# Outcome of one console write. UNKNOWN = no answer in time: the console may still have applied it.
SENT, DROPPED, UNKNOWN = "sent", "dropped", "unknown"
# The console resets the turn signal to Off whenever it deactivates (no heartbeat for 3 s; gear is kept, checked
# 10/28), so the blinker is re-sent this often even when CARLA's does not change.
TURN_RESEND_S = 1.0
PARK_DEADLINE_S = 6.0  # the final stop/Park/Off retries; docker stop allows 10 s before SIGKILL
# What a console request can fail with: urllib passes http.client errors (IncompleteRead, BadStatusLine) through.
CONSOLE_ERRORS = (OSError, http.client.HTTPException)


class SendLog:
    """--log FILE: each value a console accepted, as `wall_ms,kind,value[,value2]`, or may have taken (`kind?`: the
    write timed out). wall_ms is when the write started: the value cannot be in effect before then.
    compare_signals.py checks that the app recorded the same values."""

    def __init__(self, path: str | None) -> None:
        self.file = open(path, "w") if path else None
        self.lock = threading.Lock()  # the heartbeat thread writes too

    def write(self, started_ms: int, kind: str, *values: object, status: str = SENT) -> None:
        if self.file and status != DROPPED:
            kind += "?" if status == UNKNOWN else ""
            with self.lock:
                self.file.write(",".join([str(started_ms), kind, *map(str, values)]) + "\n")

    def close(self) -> None:
        with self.lock:  # the heartbeat thread may still write
            if self.file:
                self.file.close()
                self.file = None


def now_ms() -> int:
    return round(time.time() * 1000)


class PleosConsole:
    def __init__(self, base_url: str, dry_run: bool, log: SendLog) -> None:
        self.base_url = base_url
        self.dry_run = dry_run
        self.log = log
        self.gear = None
        self.turn = None
        self.turn_sent_at = 0.0

    def _put(self, path: str, params: dict) -> str:
        """SENT, DROPPED (503 during a console stall) or UNKNOWN (no answer). Callers re-send on their next tick."""
        url = f"{self.base_url}{path}?{urllib.parse.urlencode(params)}"
        if self.dry_run:
            return SENT
        try:
            urllib.request.urlopen(urllib.request.Request(url, method="PUT"), timeout=2).read()
            return SENT
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            if e.code != 503:  # 4xx = we sent a bad value; that's a bug, stop.
                raise SystemExit(f"Pleos console rejected {url}: {e.code} {body}") from e
            # 503 emulator_inactive: the console stalled past its 3 s heartbeat window; the heartbeat
            # thread re-activates it, and the next 10 Hz update overwrites this one.
            print(f"console PUT skipped (503 {body}): {path}")
            self.maybe_reset()
            return DROPPED
        except CONSOLE_ERRORS as e:  # timeouts, resets, RemoteDisconnected, cut-off responses
            print(f"console PUT unanswered ({e!r}): {path}")
            return UNKNOWN

    def start_heartbeat(self) -> None:
        """The console only accepts writes while a browser page heartbeats it (POST /browser/session every 1 s,
        3 s timeout). Sending the heartbeat ourselves means no browser tab is needed, and the page's own
        physics can't fight the bridge."""
        if self.dry_run:
            return
        client_id = f"carla-bridge-{uuid.uuid4()}"

        def beat() -> None:
            while True:
                try:
                    urllib.request.urlopen(
                        urllib.request.Request(f"{self.base_url}/browser/session?client={client_id}", method="POST"),
                        timeout=2,
                    ).read()
                except CONSOLE_ERRORS as e:
                    print(f"console heartbeat failed: {e!r}")
                    self.maybe_reset()
                time.sleep(1)

        threading.Thread(target=beat, daemon=True).start()
        self._wait_until_active()

    def _wait_until_active(self, timeout_s: float = 30.0) -> None:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f"{self.base_url}/vehicle/state", timeout=2) as response:
                    if json.load(response)["controls"].get("emulator_active"):
                        return
            except (*CONSOLE_ERRORS, ValueError):  # ValueError: a cut-off JSON body
                pass  # the console stalls for a few seconds at times; keep polling
            time.sleep(1)
        raise SystemExit("Pleos console inactive: is the emulator running and `adb forward tcp:8088 tcp:8088` set?")

    def set_gear(self, gear: str) -> bool:
        # Only remember the gear once the console took it: in Park it silently ignores speed updates.
        # Unanswered: forget it too, so the next tick re-sends and the console can't drift from what we think.
        if gear != self.gear:
            started = now_ms()
            status = self._put("/vehicle/gear", {"gear": gear})
            self.gear = gear if status == SENT else None
            self.log.write(started, "gear", gear, status=status)
        return self.gear == gear

    def set_turn(self, mode: str) -> bool:
        if mode != self.turn or time.monotonic() - self.turn_sent_at >= TURN_RESEND_S:
            started = now_ms()
            self.turn_sent_at = time.monotonic()
            status = self._put("/vehicle/turn-signal", {"mode": mode})
            self.turn = mode if status == SENT else None
            self.log.write(started, "turn", mode, status=status)
        return self.turn == mode

    def maybe_reset(self) -> None:
        """Signs the console may have deactivated, which turns the blinker Off: log that possibility and re-send."""
        self.log.write(now_ms(), "turn", "Off", status=UNKNOWN)
        self.turn = None

    def set_controls(self, speed_kph: float, accelerator: float, brake: float, steering_deg: float) -> bool:
        params = {
            "speed": round(speed_kph),  # the console rejects decimals ("invalid_speed")
            "accelerator": round(accelerator * 100),
            "brake": round(brake * 100),
            "steering": round(steering_deg),
        }
        started = now_ms()
        status = self._put("/vehicle/controls", params)
        # In Park (or a gear we're unsure of) the console may ignore the speed, so it is only a possibility.
        speed_status = UNKNOWN if status == SENT and self.gear in (None, "Park") else status
        self.log.write(started, "speed", params["speed"], status=speed_status)
        for kind in ("brake", "steering"):  # no accelerator: the app has no pedal signal to compare
            self.log.write(started, kind, params[kind], status=status)
        return status == SENT

    def park(self) -> None:
        """Leave the emulator stopped, in P, blinker off. One-off writes, so retry them (a 10 Hz update would
        simply be overwritten by the next one), in this order: in Park the console ignores the speed."""
        deadline = time.monotonic() + PARK_DEADLINE_S
        while time.monotonic() < deadline:
            if self.set_controls(0, 0, 0, 0) and self.set_gear("Park") and self.set_turn("Off"):
                return
            time.sleep(0.5)
        print("console did not confirm the final stop / Park / turn signal Off")


def to_lat_lon(location: carla.Location) -> tuple[float, float]:
    # CARLA: +x east, +y south (left-handed Unreal frame).
    lat = ORIGIN_LAT - location.y / METERS_PER_DEG_LAT
    lon = ORIGIN_LON + location.x / (METERS_PER_DEG_LAT * math.cos(math.radians(ORIGIN_LAT)))
    return lat, lon


class EmulatorConsole:
    """Android emulator console (port 5554). `adb emu geo fix` can't be used from a container: adb opens the
    console on its own localhost, so we speak the console protocol directly."""

    def __init__(self, host: str, port: int, token: str, dry_run: bool, log: SendLog) -> None:
        self.sock = None
        self.log = log
        if dry_run:
            return
        if not token:
            raise SystemExit("EMULATOR_TOKEN is empty: run via ./run.sh (it reads ~/.emulator_console_auth_token)")
        self.sock = socket.create_connection((host, port), timeout=3)
        self._read_ok()
        self._command(f"auth {token}")

    def _read_ok(self) -> None:
        reply = b""
        while not reply.endswith(b"OK\r\n"):
            chunk = self.sock.recv(4096)
            if not chunk or b"KO" in chunk:
                raise RuntimeError(f"emulator console: {(reply + chunk).decode(errors='replace').strip()}")
            reply += chunk

    def _command(self, line: str) -> None:
        self.sock.sendall(f"{line}\n".encode())
        self._read_ok()

    def geo_fix(self, lat: float, lon: float) -> None:
        if self.sock:
            started = now_ms()
            self._command(f"geo fix {lon:.6f} {lat:.6f}")
            self.log.write(started, "loc", f"{lat:.6f}", f"{lon:.6f}")

    def screenrecord_start(self, host_path: str, limit_s: int) -> None:
        """Records the emulator screen on the Mac side (no encoding load inside the starved guest)."""
        if self.sock:
            self._command(f"screenrecord start --size 1280x720 --time-limit {limit_s} {host_path}")

    def screenrecord_stop(self) -> None:
        if self.sock:
            self._command("screenrecord stop")


class Recorder:
    """--record DIR: CARLA bird's-eye frames + frames.csv, plus the Pleos screen (pleos.webm). The Pleos recording
    starts at frame 0, so both line up without syncing the container and Mac clocks."""

    # The emulator's cap. We stop it explicitly in close(); wall time runs longer than sim time under Rosetta.
    MAX_EMULATOR_RECORDING_S = 180

    def __init__(self, world: carla.World, emulator: EmulatorConsole, record_dir: str) -> None:
        from bev import BirdsEye  # pillow is only needed when recording

        os.makedirs(record_dir, exist_ok=True)
        self.dir = record_dir
        self.bev = BirdsEye(world)
        self.emulator = emulator
        # The emulator writes the video on the Mac, so it needs the host path of this (mounted) directory.
        self.host_video = os.path.join(os.environ["HOST_WORKDIR"], record_dir, "pleos.webm")
        self.csv = open(os.path.join(record_dir, "frames.csv"), "w")
        self.csv.write("frame,wall,phase,speed_kph,gear,light\n")
        self.count = 0

    def frame(self, ego: carla.Actor, hud: list[str], phase: str, speed_kph: float, gear: str, light: str) -> None:
        if self.count == 0:
            self.emulator.screenrecord_start(self.host_video, self.MAX_EMULATOR_RECORDING_S)
        self.bev.render(ego, hud).save(os.path.join(self.dir, f"{self.count:05d}.jpg"), quality=88)
        self.csv.write(f"{self.count},{time.time():.3f},{phase},{speed_kph:.1f},{gear},{light}\n")
        self.count += 1

    def close(self) -> None:
        self.csv.close()
        self.emulator.screenrecord_stop()


def spawn(world: carla.World, client: carla.Client) -> list[carla.Actor]:
    blueprints = world.get_blueprint_library()
    spawn_points = world.get_map().get_spawn_points()
    random.shuffle(spawn_points)
    actors = [world.spawn_actor(blueprints.find(EGO_BLUEPRINT), spawn_points[0])]
    for point in spawn_points[1 : TRAFFIC_COUNT + 1]:
        npc = world.try_spawn_actor(random.choice(blueprints.filter("vehicle.*")), point)
        if npc:
            actors.append(npc)
    tm_port = client.get_trafficmanager().get_port()
    for actor in actors[1:]:  # the ego's autopilot starts with the drive phase in drive()
        actor.set_autopilot(True, tm_port)
    return actors


def nearby(world: carla.World, ego: carla.Actor) -> list[tuple[str, float]]:
    here = ego.get_location()
    found = [
        (a.type_id.split(".")[-1], round(a.get_location().distance(here), 1))
        for a in world.get_actors().filter("vehicle.*")
        if a.id != ego.id and a.get_location().distance(here) < NEARBY_METERS
    ]
    return sorted(found, key=lambda item: item[1])


def turn_mode(ego: carla.Actor) -> str:
    """CARLA blinker lights (set by the traffic manager, see update_vehicle_lights) -> console turn-signal mode."""
    lights = int(ego.get_light_state())
    left = bool(lights & int(carla.VehicleLightState.LeftBlinker))
    right = bool(lights & int(carla.VehicleLightState.RightBlinker))
    if left != right:
        return "BlinkLeft" if left else "BlinkRight"
    return "Off"  # off, or hazards (both): the app's turn signal has no hazard state


def send(
    tick: int, phase: str, world: carla.World, ego: carla.Actor,
    console: PleosConsole, emulator: EmulatorConsole, recorder: Recorder | None,
) -> None:
    """Mirror the ego state into Pleos (10 Hz controls, 1 Hz GPS) and record a frame if asked."""
    control = ego.get_control()
    # The traffic manager reports NaN brake while it holds a car at 0 km/h.
    throttle, brake, steer = (0.0 if math.isnan(x) else x for x in (control.throttle, control.brake, control.steer))
    v = ego.get_velocity()
    speed_kph = 3.6 * math.sqrt(v.x**2 + v.y**2 + v.z**2)
    if phase == "park" or (phase == "stop" and speed_kph < 1.0):
        gear = "Park"
    else:
        gear = "Reverse" if control.reverse else "Drive"
    console.set_gear(gear)
    turn = turn_mode(ego)
    console.set_turn(turn)
    console.set_controls(speed_kph, throttle, brake, steer * STEERING_WHEEL_MAX_DEG)
    light = str(ego.get_traffic_light_state()) if ego.is_at_traffic_light() else "-"
    close = nearby(world, ego)

    if tick % GPS_EVERY_TICKS == 0:
        lat, lon = to_lat_lon(ego.get_location())
        emulator.geo_fix(lat, lon)
        print(
            f"{speed_kph:5.1f} km/h  {phase:5s} {gear:7s} thr={throttle:.2f} brk={brake:.2f} "
            f"steer={steer:+.2f} turn={turn} light={light}  gps=({lat:.5f},{lon:.5f})  nearby={close[:3]}"
        )
    if recorder:
        hud = [
            f"CARLA  t={tick * TICK_SECONDS:5.1f}s   phase: {phase}",
            f"ego {speed_kph:3.0f} km/h    -> Pleos gear {gear[0]}",
            f"throttle {throttle:.2f}   brake {brake:.2f}",
            f"steer {steer:+.2f}    light: {light}",
            f"nearest vehicle: {close[0][1]:.0f} m" if close else "nearest vehicle: -",
        ]
        recorder.frame(ego, hud, phase, speed_kph, gear, light)


def drive(
    world: carla.World, ego: carla.Actor, tm: carla.TrafficManager, phases: list[tuple[str, float]],
    console: PleosConsole, emulator: EmulatorConsole, recorder: Recorder | None,
) -> None:
    """park: ego held still in P | drive: ego on autopilot in traffic | stop: halt, then P."""
    tick = 0
    next_tick = time.monotonic()
    for phase, duration in phases:
        if phase == "drive":
            ego.set_autopilot(True, tm.get_port())
            tm.update_vehicle_lights(ego, True)  # the traffic manager then sets blinkers before turns
        elif phase == "stop":
            # set_autopilot(False) doesn't reliably release the car in sync mode (CARLA 0.9.15: it keeps its
            # last throttle and ignores apply_control), so ask the traffic manager for 0 km/h instead.
            tm.vehicle_percentage_speed_difference(ego, 100.0)
        for _ in range(int(duration / TICK_SECONDS)):
            if phase == "park":
                ego.apply_control(carla.VehicleControl(throttle=0.0, brake=1.0))
            world.tick()
            next_tick += TICK_SECONDS
            time.sleep(max(0.0, next_tick - time.monotonic()))  # pace the simulation to wall-clock time
            if tick % SEND_EVERY_TICKS == 0:
                send(tick, phase, world, ego, console, emulator, recorder)
            tick += 1


def run(seconds: float, dry_run: bool, record_dir: str | None, log_path: str | None) -> None:
    client = carla.Client(os.environ.get("CARLA_HOST", "localhost"), 2000)
    client.set_timeout(120.0)
    world = client.get_world()
    print(f"CARLA server {client.get_server_version()}, map {world.get_map().name}")
    log = SendLog(log_path)
    console = PleosConsole(os.environ.get("PLEOS_CONSOLE", "http://localhost:8088"), dry_run, log)

    console.start_heartbeat()
    emulator = EmulatorConsole(
        os.environ.get("EMULATOR_HOST", "localhost"),
        int(os.environ.get("EMULATOR_CONSOLE_PORT", "5554")),
        os.environ.get("EMULATOR_TOKEN", ""),
        dry_run,
        log,
    )

    # Synchronous mode: CARLA only advances when we tick, in fixed steps, paced to wall-clock time below.
    original_settings = world.get_settings()
    settings = world.get_settings()
    settings.synchronous_mode = True
    settings.fixed_delta_seconds = TICK_SECONDS
    world.apply_settings(settings)
    client.get_trafficmanager().set_synchronous_mode(True)

    actors = spawn(world, client)
    ego = actors[0]
    tm = client.get_trafficmanager()
    tm_port = tm.get_port()
    print(f"spawned ego + {len(actors) - 1} traffic vehicles{' (dry run)' if dry_run else ''}")
    recorder = None
    if record_dir:
        recorder = Recorder(world, emulator, record_dir)
        phases = [("park", PREROLL_S), ("drive", seconds), ("stop", POSTROLL_S)]
    else:
        phases = [("drive", seconds)]
    try:
        drive(world, ego, tm, phases, console, emulator, recorder)
    finally:
        try:
            console.park()
        finally:  # CARLA must be released even if the console hangs or raises
            log.close()
            if recorder:
                recorder.close()
            # Detach from the traffic manager first, or it crashes looking up destroyed actors.
            client.apply_batch([carla.command.SetAutopilot(a, False, tm_port) for a in actors])
            client.apply_batch_sync([carla.command.DestroyActor(a) for a in actors])
            client.get_trafficmanager().set_synchronous_mode(False)
            world.apply_settings(original_settings)  # don't leave the server frozen waiting for ticks


if __name__ == "__main__":
    # `docker stop` sends SIGTERM; turn it into SystemExit so cleanup restores async mode.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--seconds", type=float, default=60.0)
    parser.add_argument("--dry-run", action="store_true", help="CARLA only; don't touch the Pleos emulator")
    parser.add_argument("--record", metavar="DIR", help="relative dir: CARLA bird's-eye frames + Pleos screen video")
    parser.add_argument("--log", metavar="FILE", help="relative path: CSV of every value the consoles accepted")
    args = parser.parse_args()
    if args.record and (args.dry_run or os.path.isabs(args.record)):
        parser.error("--record needs the emulator and a path relative to tools/carla")
    if args.log and (args.dry_run or os.path.isabs(args.log)):
        parser.error("--log needs the emulator and a path relative to tools/carla (the container sees only that)")
    run(args.seconds, args.dry_run, args.record, args.log)

"""The road-test courses of 강남운전면허시험장 on the daechi map (bridge.py --course A|B|C|D).

Where each course goes was read from 도로교통공단's guide videos for the four courses (koroad.or.kr, 도로주행 안내
동영상, posted 2023-01-31: the voice script's turn order) and a driving school's sketch of each course (드라이빙존,
drivingzone.co.kr 시험장 코스분석, 2025-07): which roads and junctions, in order. Only that fact is used here, as
points on the OpenStreetMap roads; neither the videos nor the sketches are copied into this repo. Both sources say
the real test can differ.

The site's own yard is not on the map: a course starts on 테헤란로114길 at the site's gate (A, C, D leave it turning
right, south; B turning left, north) and ends there coming back north, where the examinee turns left into the yard.

A course is a list of points the route must pass, each on the carriageway it is driven in (lat, lon, compass
heading). Between them the route is the shortest way on the lanes, changing lanes where it must: a left turn is only
made from the left lanes of a converted map's junction, as in Korea. U-turns are the converter's turnarounds at
junctions (SUMO makes them where a divided road's two carriageways meet a junction).
"""
from __future__ import annotations

import heapq
import math
from typing import NamedTuple, Optional

import carla

STEP_M = 2.0  # the driver's plan spacing (driver.py STEP_M)
# A route point is on the road (one carriageway of it: the converter makes each direction its own road) of the
# nearest lane heading its way within VIA_HEADING_DEG, if that lane is this close: points are read off OSM by hand.
NEAR_VIA_M = 40.0
VIA_HEADING_DEG = 45.0
# Lane changes: each takes LANE_CHANGE_M of road, and is made at least CLEAR_AFTER_M after the junction before and
# CLEAR_BEFORE_M before the next. The app judges turn signals 30 m around a turn's middle (Turns.kt): a lane-change
# blinker inside that would read as a turn signal switched on late, off early or left on (31~33).
LANE_CHANGE_M = 25.0
LANE_GAP_M = 15.0  # between two lane changes in a row
CLEAR_AFTER_M = 50.0  # the driver blinks CHANGE_BLINK_M before the new lane: still 35 m past the junction
CLEAR_BEFORE_M = 60.0  # ...and stops when in it, 20 m before it blinks for the turn (driver.py BLINK_BEFORE_M)
CHANGE_COST_M = 30.0  # a lane change costs this much route, so the route makes no more of them than it must
SHORT_SECTION_M = 120.0  # ...and more in a section shorter than this, so it changes lanes where there is room
TURN_DEG = 30.0  # driver.py's: a junction the route turns through more than this is a turn, past UTURN_DEG a U-turn
UTURN_DEG = 150.0
# Road this short between two junctions is part of one junction: a divided road's two carriageways meet a street as
# two junctions (대치우성아파트: 26 m apart), and a slip lane turns right through two (아시아선수촌: 25 m).
JOIN_M = 40.0

# (lat, lon, compass heading of the carriageway). 영동대로 runs NNW-SSE (about 160° southbound), 역삼로 and 도곡로 ENE,
# 백제고분로 NNW, 올림픽로 E.
GATE_S = (37.50830, 127.06703, 162.0)  # 테헤란로114길 at the site's gate, southbound
GATE_N = (37.50830, 127.06703, 342.0)  # ...northbound
GATE_BACK = (37.50860, 127.06692, 342.0)  # coming back: stop by the gate, northbound
YEONGDONG_S_OF_HWIMUN = (37.50380, 127.06580, 160.0)  # 영동대로 southbound past 휘문고교 (left from 역삼로)
YEONGDONG_N_OF_DAECHI = (37.50250, 127.06680, 340.0)  # 영동대로 northbound past 대치우성아파트
YEOKSAM_E_OF_HWIMUN = (37.50560, 127.06640, 68.0)  # 역삼로 eastbound past 휘문고교 (right from 영동대로)
DOGOK_E = (37.50180, 127.07000, 69.0)  # 도곡로 eastbound past 대치우성아파트
DOGOK_W = (37.50235, 127.07158, 247.0)  # 도곡로 westbound on 탄천2교, back from 아시아선수촌
BAEKJE_N = (37.50850, 127.07920, 350.0)  # 백제고분로 northbound past 아시아선수촌
BAEKJE_S = (37.50950, 127.07885, 170.0)  # 백제고분로 southbound, back from 종합운동장
OLYMPIC_E = (37.51030, 127.07000, 80.0)  # 올림픽로 eastbound toward 삼성교
OLYMPIC_W = (37.51170, 127.08500, 262.0)  # 올림픽로 westbound, back from 잠실새내역

COURSES: dict[str, list[tuple[float, float, float]]] = {
    # 휘문고교 left, 영동대로 south to a U-turn at 일원터널, back north and right at 휘문고교
    "A": [GATE_S, YEONGDONG_S_OF_HWIMUN, (37.48800, 127.07450, 336.0), YEOKSAM_E_OF_HWIMUN, GATE_BACK],
    # north and right onto 올림픽로 over 삼성교, U-turn at 잠실새내역, left at 종합운동장 onto 백제고분로, right at
    # 아시아선수촌 onto 도곡로 over 탄천2교, right at 대치우성아파트, right at 휘문고교
    "B": [GATE_N, OLYMPIC_E, OLYMPIC_W, BAEKJE_S, DOGOK_W, YEONGDONG_N_OF_DAECHI, YEOKSAM_E_OF_HWIMUN, GATE_BACK],
    # 휘문고교 left, 대치우성아파트 left, 아시아선수촌 left, U-turn at 종합운동장, then right, right, right home
    "C": [GATE_S, YEONGDONG_S_OF_HWIMUN, DOGOK_E, BAEKJE_N, BAEKJE_S, DOGOK_W, YEONGDONG_N_OF_DAECHI,
          YEOKSAM_E_OF_HWIMUN, GATE_BACK],
    # 휘문고교 left, U-turn at 학여울역, right at 대치우성아파트, U-turn at 아시아선수촌, right, right home
    "D": [GATE_S, YEONGDONG_S_OF_HWIMUN, (37.49800, 127.06900, 337.0), DOGOK_E, DOGOK_W, YEONGDONG_N_OF_DAECHI,
          YEOKSAM_E_OF_HWIMUN, GATE_BACK],
}

# Turns the converter leaves out of a map's junctions, added back (odr_links.py): (from point, to point).
MAP_LINKS: dict[str, list[tuple[tuple[float, float, float], tuple[float, float, float]]]] = {
    "daechi": [
        ((37.50531, 127.06540, 248.0), (37.50500, 127.06502, 160.0)),  # 휘문고교: 역삼로 westbound, left onto 영동대로
        ((37.50965, 127.06650, 345.0), (37.50978, 127.06660, 75.0)),  # 테헤란로114길 north end, right onto 테헤란로
    ],
}


class Point(NamedTuple):
    wp: carla.Waypoint
    change: Optional[str]  # "left" | "right" on the first point of the lane the car changes into
    junction: bool = False  # in a junction, or on road short enough between two to be part of one (JOIN_M)


def yaw_diff(a: float, b: float) -> float:
    return (b - a + 180.0) % 360.0 - 180.0


class Geo:
    """lat/lon -> CARLA location, from the map's georeference (linear: a few km of transverse Mercator)."""

    def __init__(self, world_map: carla.Map) -> None:
        def geo(x: float, y: float) -> tuple[float, float]:
            g = world_map.transform_to_geolocation(carla.Location(x, y, 0.0))
            return g.latitude, g.longitude

        self.origin = geo(0.0, 0.0)
        (ax, bx), (ay, by) = [(a - self.origin[0], b - self.origin[1]) for a, b in (geo(1000.0, 0.0), geo(0.0, 1000.0))]
        self.m = (ax / 1000, ay / 1000, bx / 1000, by / 1000)  # dlat = m0 x + m1 y, dlon = m2 x + m3 y

    def location(self, lat: float, lon: float) -> carla.Location:
        m0, m1, m2, m3 = self.m
        dlat, dlon = lat - self.origin[0], lon - self.origin[1]
        det = m0 * m3 - m1 * m2
        return carla.Location((m3 * dlat - m1 * dlon) / det, (m0 * dlon - m2 * dlat) / det, 0.0)


def compass(yaw: float) -> float:
    """CARLA yaw (x east, y south) -> compass degrees."""
    return (yaw + 90.0) % 360.0


class Lanes:
    """The map's lanes as a graph: a node per lane of a road section, its successors, and lane changes to a
    neighbour lane. (CARLA's topology lists a lane once for each lane it leads to.)"""

    def __init__(self, world_map: carla.Map) -> None:
        self.map = world_map
        key: dict[tuple[int, int, int], int] = {}
        self.segments: list[list[carla.Waypoint]] = []
        exits: list[list[carla.Waypoint]] = []
        for a, b in world_map.get_topology():
            if self._key(a) not in key:
                key[self._key(a)] = len(self.segments)
                self.segments.append([a] + a.next_until_lane_end(STEP_M))
                exits.append([])
            exits[key[self._key(a)]].append(b)
        self.length = [sum(p.transform.location.distance(q.transform.location) for p, q in zip(s, s[1:]))
                       for s in self.segments]
        self.next: list[list[int]] = []
        self.side: list[list[tuple[int, str]]] = []
        for seg, ends in zip(self.segments, exits):
            # The exit waypoint is where the next lane starts, or else the end of this one.
            after = [w for b in ends for w in ([b] if self._key(b) != self._key(seg[0]) else b.next(1.0))]
            self.next.append(sorted({key[k] for k in map(self._key, after) if k in key}))
            sides = []
            if not seg[0].is_junction:
                for lane, way in ((seg[0].get_left_lane(), "left"), (seg[0].get_right_lane(), "right")):
                    if (lane is not None and lane.lane_type == carla.LaneType.Driving
                            and lane.lane_id * seg[0].lane_id > 0 and self._key(lane) in key):
                        sides.append((key[self._key(lane)], way))
            self.side.append(sides)

    @staticmethod
    def _key(wp: carla.Waypoint) -> tuple[int, int, int]:
        return wp.road_id, wp.section_id, wp.lane_id

    def passing(self, lat_lon_heading: tuple[float, float, float], geo: Geo) -> set[int]:
        """The lanes of the road a route point is on, in its direction."""
        lat, lon, heading = lat_lon_heading
        here = geo.location(lat, lon)
        near = [(math.hypot(w.transform.location.x - here.x, w.transform.location.y - here.y), w.road_id)
                for seg in self.segments if not seg[0].is_junction for w in seg
                if abs(yaw_diff(heading, compass(w.transform.rotation.yaw))) < VIA_HEADING_DEG]
        distance, road = min(near, default=(math.inf, None))
        if distance > NEAR_VIA_M:
            return set()
        return {i for i, seg in enumerate(self.segments) if seg[0].road_id == road}

    def shortest(self, start: int, targets: set[int]) -> list[tuple[int, str]]:
        """[(segment, how it was entered: "start" | "next" | "left" | "right")] from [start] to any of [targets].
        Distances are to a segment's end; a lane change swaps the segment for its neighbour."""
        best = {start: self.length[start]}
        came: dict[int, tuple[int, str]] = {}
        queue = [(self.length[start], start)]
        while queue:
            d, i = heapq.heappop(queue)
            if d > best[i]:
                continue
            if i in targets and i != start:
                path = []
                while i != start:
                    prev, how = came[i]
                    path.append((i, how))
                    i = prev
                return [(start, "start")] + path[::-1]
            moves = [(j, "next", d + self.length[j]) for j in self.next[i]]
            short = max(0.0, SHORT_SECTION_M - self.length[i])
            moves += [(j, way, d - self.length[i] + self.length[j] + CHANGE_COST_M + short)
                      for j, way in self.side[i]]
            for j, how, dj in moves:
                if dj < best.get(j, math.inf):
                    best[j], came[j] = dj, (i, how)
                    heapq.heappush(queue, (dj, j))
        raise ValueError(f"no route from lane segment {start} to {sorted(targets)[:5]}")


def route(world_map: carla.Map, points: list[tuple[float, float, float]]) -> list[Point]:
    """The course through [points] as the driver's plan: waypoints STEP_M apart, from the first point's lane to where
    the last one is passed, with each lane change marked on the lane it goes into."""
    lanes, geo = Lanes(world_map), Geo(world_map)
    first = geo.location(*points[0][:2])
    starts = lanes.passing(points[0], geo)
    if not starts:
        raise ValueError(f"no lane at the course start {points[0]}")
    # Start in the kerb lane, as a car pulling out does: the rightmost of the start's lanes.
    start = min(starts, key=lambda i: (len([j for j, way in lanes.side[i] if way == "right"]),
                                       lanes.segments[i][0].transform.location.distance(first)))
    path = [(start, "start")]
    for point in points[1:]:
        targets = lanes.passing(point, geo)
        if not targets:
            raise ValueError(f"no lane passes {point}")
        if path[-1][0] in targets:  # already on that road: the point is further along it
            continue
        try:
            path += lanes.shortest(path[-1][0], targets)[1:]
        except ValueError as e:
            raise ValueError(f"{e} (on the way to {point})") from None
    out = _points(lanes, path)
    # The ends are looked for near their own end of the course: a course leaves and comes back by the same gate.
    half = len(out) // 2
    begin = min(range(half), key=lambda k: out[k].wp.transform.location.distance(first))
    end_at = geo.location(*points[-1][:2])
    end = min(range(half, len(out)), key=lambda k: out[k].wp.transform.location.distance(end_at))
    # A course can drive a lane twice (B leaves and comes back by 테헤란로114길): the driver tells its points apart by
    # object, so a waypoint met again is fetched anew.
    seen: set[int] = set()
    points = []
    for p in out[begin:end + 1]:
        if id(p.wp) in seen:
            p = p._replace(wp=world_map.get_waypoint_xodr(p.wp.road_id, p.wp.lane_id, p.wp.s) or p.wp)
        seen.add(id(p.wp))
        points.append(p)
    return _join_junctions(points)


def _join_junctions(points: list[Point]) -> list[Point]:
    flags = [p.wp.is_junction for p in points]
    k = 0
    while k < len(points):
        if flags[k]:
            k += 1
            continue
        end = k
        while end < len(points) and not points[end].wp.is_junction:
            end += 1
        if 0 < k and end < len(points) and sum(points[i].wp.transform.location.distance(points[i + 1].wp.transform.location)
                                                for i in range(k - 1, end)) < JOIN_M:
            flags[k:end] = [True] * (end - k)
        k = end
    return [p._replace(junction=f) for p, f in zip(points, flags)]


def _points(lanes: Lanes, path: list[tuple[int, str]]) -> list[Point]:
    out: list[Point] = []
    k = 0
    while k < len(path):
        run = [path[k]]  # a segment, then the neighbours it changes into, all side by side in one section
        while k + 1 < len(path) and path[k + 1][1] in ("left", "right"):
            k += 1
            run.append(path[k])
        k += 1
        first = lanes.segments[run[0][0]]
        if len(run) == 1:
            out += [Point(w, None) for w in first]
            continue
        s0, length = first[0].s, lanes.length[run[0][0]]
        changes = len(run) - 1
        need = changes * LANE_CHANGE_M + (changes - 1) * LANE_GAP_M
        if length - CLEAR_AFTER_M - CLEAR_BEFORE_M < need:  # its blinker would fall in a turn's window in the app
            raise ValueError(f"{changes} lane change(s) needed on {length:.0f} m of road {first[0].road_id}")
        at = CLEAR_AFTER_M
        for c, (seg, how) in enumerate(run):
            begin = at + (c - 1) * (LANE_CHANGE_M + LANE_GAP_M) + LANE_CHANGE_M if c else -1.0
            stop = at + c * (LANE_CHANGE_M + LANE_GAP_M) if c < changes else math.inf
            kept = [w for w in lanes.segments[seg] if begin <= abs(w.s - s0) < stop]
            out += [Point(w, how if c and i == 0 else None) for i, w in enumerate(kept)]
    return out


class Turn(NamedTuple):
    kind: str  # "left" | "right" | "uturn"
    lat: float
    lon: float


def turns(points: list[Point], world_map: carla.Map) -> list[Turn]:
    """The junctions the course turns through, in order, located at their middle."""
    out = []
    k = 1
    while k < len(points):
        if not points[k].junction:
            k += 1
            continue
        end = k
        while end < len(points) and points[end].junction:
            end += 1
        if end == len(points):
            break
        d = yaw_diff(points[k - 1].wp.transform.rotation.yaw, points[end].wp.transform.rotation.yaw)
        if abs(d) > TURN_DEG:
            g = world_map.transform_to_geolocation(points[(k + end) // 2].wp.transform.location)
            kind = "uturn" if abs(d) > UTURN_DEG else "right" if d > 0 else "left"
            out.append(Turn(kind, round(g.latitude, 5), round(g.longitude, 5)))
        k = end
    return out


def lane_changes(points: list[Point]) -> list[str]:
    return [p.change for p in points if p.change]

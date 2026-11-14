"""Junction connections CARLA's OSM converter leaves out, added back to its OpenDRIVE (course.py MAP_LINKS).

The converter (SUMO's netconvert inside CARLA) drops some turns at junctions, most where a divided road's two
carriageways cross a street as two junctions: on the daechi map, 역삼로 westbound could not turn left onto 영동대로
at 휘문고교, a turn every course but B makes. Each link here is a road in the junction from the end of one lane to
the start of another: a cubic (Hermite) curve leaving and arriving along the lanes. CARLA then makes waypoints and road
surface for it like any other.
"""
from __future__ import annotations

import math
import re

import carla

from course import Geo, compass, yaw_diff

NEAR_M = 30.0  # a link's end is the lane end (start) nearest its point within this, heading its way
HEADING_DEG = 45.0
TURN_DEG = 30.0


def _lane_ends(world_map: carla.Map, xodr: str) -> tuple[list[carla.Waypoint], list[carla.Waypoint]]:
    """Where the lanes of the roads outside junctions end and start (the converter's lanes all run along s)."""
    ends, starts = [], []
    for attrs in re.findall(r'<road ([^>]*\bjunction="-1"[^>]*)>', xodr):
        road, length = int(re.search(r'\bid="(\d+)"', attrs).group(1)), float(re.search(r'length="([^"]+)"', attrs).group(1))
        for lane in range(-1, -10, -1):
            end = world_map.get_waypoint_xodr(road, lane, max(0.0, length - 0.01))
            if end is None:
                break
            if end.lane_type == carla.LaneType.Driving:  # not the sidewalk the converter puts outside them
                ends.append(end)
                starts.append(world_map.get_waypoint_xodr(road, lane, 0.01))
    return ends, [w for w in starts if w is not None]


def _pick(wps: list[carla.Waypoint], point: tuple[float, float, float], geo: Geo, side: str) -> carla.Waypoint:
    """The lane nearest [point] heading its way; of its road's lanes there, the leftmost for a left turn (and
    straight on), the rightmost for a right turn."""
    lat, lon, heading = point
    here = geo.location(lat, lon)
    near = [(w.transform.location.distance(here), w) for w in wps
            if abs(yaw_diff(heading, compass(w.transform.rotation.yaw))) < HEADING_DEG]
    distance, nearest = min(near, key=lambda dw: dw[0], default=(math.inf, None))
    if distance > NEAR_M:
        raise ValueError(f"no lane end within {NEAR_M} m of {point}")
    same = [w for d, w in near if w.road_id == nearest.road_id and d < NEAR_M + 10]
    going_on = [w for w in same if _goes_on(w)] or same  # not into a lane that ends at the next junction
    return (max if side == "right" else min)(going_on, key=lambda w: abs(w.lane_id))


def _goes_on(wp: carla.Waypoint) -> bool:
    end = (wp.next_until_lane_end(2.0) or [wp])[-1]
    return bool(end.next(2.0))


def _odr_pose(wp: carla.Waypoint) -> tuple[float, float, float]:
    """The lane's left edge at [wp] in OpenDRIVE coordinates (CARLA's y is OpenDRIVE's −y): where a one-lane road
    whose lane −1 runs down the middle of this lane has its reference line."""
    loc, h = wp.transform.location, -math.radians(wp.transform.rotation.yaw)
    half = wp.lane_width / 2
    return loc.x - half * math.sin(h), -loc.y + half * math.cos(h), h


def _hermite(start: tuple[float, float, float], end: tuple[float, float, float]) -> tuple[list[float], float]:
    """paramPoly3 coefficients (aU..dU, aV..dV; normalised p) from [start] to [end] (x, y, heading), and the length."""
    x0, y0, h0 = start
    x1, y1, h1 = end
    dx, dy = x1 - x0, y1 - y0
    u1, v1 = dx * math.cos(h0) + dy * math.sin(h0), -dx * math.sin(h0) + dy * math.cos(h0)
    he = h1 - h0
    k = math.hypot(u1, v1)  # tangent length: the chord, a smooth quarter turn
    cu, su = k * math.cos(he), k * math.sin(he)
    coeffs = [0.0, k, 3 * u1 - 2 * k - cu, -2 * u1 + k + cu, 0.0, 0.0, 3 * v1 - su, -2 * v1 + su]

    def at(t: float) -> tuple[float, float]:
        return (sum(c * t**i for i, c in enumerate(coeffs[:4])), sum(c * t**i for i, c in enumerate(coeffs[4:])))

    samples = [at(i / 200) for i in range(201)]
    length = sum(math.dist(p, q) for p, q in zip(samples, samples[1:]))
    return coeffs, length


def add_links(xodr: str, links: list[tuple[tuple[float, float, float], tuple[float, float, float]]]) -> str:
    """[xodr] with a connecting road for each (from point, to point): from the end of the lane passing the first to
    the start of the lane passing the second, in the junction the first lane runs into."""
    if not links:
        return xodr
    world_map = carla.Map("links", xodr)
    geo = Geo(world_map)
    ends, starts = _lane_ends(world_map, xodr)
    next_road = max(int(i) for i in re.findall(r'<road [^>]*\bid="(\d+)"', xodr)) + 1
    roads, connections = [], {}
    for k, (frm, to) in enumerate(links):
        turn = yaw_diff(frm[2], to[2])
        side = "right" if TURN_DEG < turn <= 150 else "left"
        a, b = _pick(ends, frm, geo, side), _pick(starts, to, geo, side)
        block = re.search(rf'<road [^>]*\bid="{a.road_id}"[^>]*>(.*?)</road>', xodr, re.S)
        junction = re.search(r'<successor elementType="junction" elementId="([^"]+)"', block.group(1)) if block else None
        if junction is None:
            raise ValueError(f"lane end {a.road_id}/{a.lane_id} near {frm} does not run into a junction")
        coeffs, length = _hermite(_odr_pose(a), _odr_pose(b))
        x, y, h = _odr_pose(a)
        rid = next_road + k
        poly = " ".join(f'{n}="{c:.9f}"' for n, c in zip(("aU", "bU", "cU", "dU", "aV", "bV", "cV", "dV"), coeffs))
        roads.append(f'''    <road name="link" length="{length:.6f}" id="{rid}" junction="{junction.group(1)}">
        <link>
            <predecessor elementType="road" elementId="{a.road_id}" contactPoint="end"/>
            <successor elementType="road" elementId="{b.road_id}" contactPoint="start"/>
        </link>
        <planView>
            <geometry s="0.0" x="{x:.6f}" y="{y:.6f}" hdg="{h:.9f}" length="{length:.6f}">
                <paramPoly3 {poly} pRange="normalized"/>
            </geometry>
        </planView>
        <elevationProfile>
            <elevation s="0.0" a="{a.transform.location.z:.3f}" b="0.0" c="0.0" d="0.0"/>
        </elevationProfile>
        <lanes>
            <laneSection s="0.0">
                <center>
                    <lane id="0" type="none" level="false"/>
                </center>
                <right>
                    <lane id="-1" type="driving" level="false">
                        <link>
                            <predecessor id="{a.lane_id}"/>
                            <successor id="{b.lane_id}"/>
                        </link>
                        <width sOffset="0.0" a="{a.lane_width:.3f}" b="{(b.lane_width - a.lane_width) / length:.6f}" c="0.0" d="0.0"/>
                    </lane>
                </right>
            </laneSection>
        </lanes>
    </road>
''')
        connections.setdefault(junction.group(1), []).append(
            f'        <connection id="{rid}" incomingRoad="{a.road_id}" connectingRoad="{rid}" contactPoint="start">\n'
            f'            <laneLink from="{a.lane_id}" to="-1"/>\n        </connection>\n')
    for jid, conns in connections.items():
        head = re.search(rf'<junction [^>]*\bid="{re.escape(jid)}"[^>]*>', xodr)
        if head is None:
            raise ValueError(f"junction {jid} not found")
        xodr = xodr[:head.end()] + "\n" + "".join(conns) + xodr[head.end():]
    first_junction = xodr.index("<junction ")
    return xodr[:first_junction] + "".join(roads) + "    " + xodr[first_junction:]

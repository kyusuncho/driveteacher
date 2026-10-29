"""Korean road facts for the CARLA bridge on a real-city map (bridge.py --map): what the car's own navigation would
say through NaviHelper, which the Pleos emulator cannot (its built-in navigation never follows injected GPS). The
bridge sends these on the simulator channel (③) and the app's debug build takes them in place of the car's.

  - Map: an OpenStreetMap extract of the drivable roads (maps/<name>.osm, © OpenStreetMap contributors, ODbL).
    Roads without a speed limit get the Korean urban one (안전속도 5030) before CARLA converts the map.
  - Enforcement cameras: 경찰청 「전국무인교통단속카메라표준데이터」 (data.go.kr 15028200), cut to the map
    (maps/<name>-cameras.csv). Codes as read from the data (the standard's code table was not found):
    단속구분 1 speed, 2 signal (with a speed limit), 3/4 passage and parking rules (local governments), 99 other;
    보호구역구분 2 school zone, 1 senior zone, 99 none; 단속구간위치구분 1/2 section start/end.

Build the map files once, on the Mac (no CARLA needed):
  curl -o raw.osm 'https://api.openstreetmap.org/api/0.6/map?bbox=127.0240,37.4950,127.0400,37.5030'
  python3 traffic_info.py roads raw.osm maps/gangnam.osm
  python3 traffic_info.py cameras ../../data/전국무인교통단속카메라표준데이터.csv maps/gangnam.osm maps/gangnam-cameras.csv
"""
from __future__ import annotations

import csv
import html
import math
import re
import sys
import xml.etree.ElementTree as ET
from typing import Dict, Iterable, List, NamedTuple, Optional, Tuple

# OSM highway types CARLA drives on; the map conversion uses the same list.
ROAD_TYPES = [
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential",
]
# 안전속도 5030 (since 2021-04-17): 50 km/h on urban roads, 30 on side streets. Expressways keep their own.
KOREA_URBAN_KPH = {"motorway": 100, "motorway_link": 80, "trunk": 80, "residential": 30}
URBAN_DEFAULT_KPH = 50
METERS_PER_DEG_LAT = 111_320.0
# ponytail: navigation apps announce a camera some hundreds of metres ahead; NaviHelper's distance is undocumented.
ALERT_M = 500.0
AHEAD_DEG = 45.0  # a camera this far off the heading is not "ahead"
CAMERA_FIELDS = ["id", "lat", "lon", "road", "type", "limit_kph", "place"]
# Who edited what, when: not needed to drive, and the mappers' account names need not be republished.
EDIT_HISTORY = ("user", "uid", "changeset", "timestamp", "version", "visible")


class Camera(NamedTuple):
    id: str
    lat: float
    lon: float
    road: str  # 도로노선명, matched against the OpenDRIVE road names (taken from OSM's)
    type: str  # NaviHelper CameraType name
    limit_kph: int
    place: str  # 설치장소, for people reading the file


def _road_ways(root: ET.Element) -> Iterable[ET.Element]:
    for way in root.iter("way"):
        tags = {t.get("k"): t.get("v") for t in way.iter("tag")}
        if tags.get("highway") in ROAD_TYPES:
            yield way


def roads_only(osm: str) -> str:
    """The drivable roads of an OSM extract, with their nodes and the bounds, without the edit history: what the
    repo keeps."""
    root = ET.fromstring(osm)
    ways = list(_road_ways(root))
    used = {nd.get("ref") for way in ways for nd in way.iter("nd")}
    for element in root:
        for name in EDIT_HISTORY:
            element.attrib.pop(name, None)
    out = ET.Element("osm", root.attrib)
    out.extend([e for e in root if e.tag == "bounds"])
    out.extend([n for n in root.iter("node") if n.get("id") in used])
    out.extend(ways)
    return ET.tostring(out, encoding="unicode")


def fill_speed_limits(osm: str) -> str:
    """Adds the Korean urban limit to roads that have none; CARLA's converter would put SUMO's defaults there
    (100 km/h on 테헤란로)."""
    root = ET.fromstring(osm)
    for way in _road_ways(root):
        tags = {t.get("k") for t in way.iter("tag")}
        if "maxspeed" not in tags:
            highway = next(t.get("v") for t in way.iter("tag") if t.get("k") == "highway")
            ET.SubElement(way, "tag", k="maxspeed", v=str(KOREA_URBAN_KPH.get(highway, URBAN_DEFAULT_KPH)))
    return ET.tostring(root, encoding="unicode")


def osm_bounds(osm: str) -> Tuple[float, float, float, float]:
    b = ET.fromstring(osm).find("bounds")
    return float(b.get("minlat")), float(b.get("minlon")), float(b.get("maxlat")), float(b.get("maxlon"))


def road_limits(xodr: str) -> Dict[int, Tuple[str, int]]:
    """OpenDRIVE road id -> (name, km/h of its fastest lane), for roads outside junctions. CARLA's own
    get_speed_limit() reads only speed signs, which a converted map has none of (it says 30 everywhere)."""
    out = {}
    for m in re.finditer(r'<road name="([^"]*)"[^>]*\bid="(\d+)" junction="-1">(.*?)</road>', xodr, re.S):
        speeds = [float(s) for s in re.findall(r'<speed sOffset="[^"]*" max="([^"]+)"', m.group(3))]
        if speeds:
            out[int(m.group(2))] = (html.unescape(m.group(1)), round(max(speeds) * 3.6))
    return out


def _codes(value: str) -> set:
    return {part.lstrip("0") for part in value.split("+") if part}


def camera_type(row: dict) -> Optional[str]:
    """A data row -> NaviHelper CameraType name, or None for a camera that does not enforce a speed limit."""
    try:
        limit = int(row["제한속도"] or 0)
    except ValueError:
        return None
    codes, section = _codes(row["단속구분"]), _codes(row["단속구간위치구분"])
    school = _codes(row["보호구역구분"]) == {"2"}
    if limit <= 0:
        return None
    if section == {"1"}:
        return "SPEED_CAMERA_SECTION_START"
    if section == {"2"}:
        return "SPEED_CAMERA_SECTION_END"
    if "2" in codes:
        return "SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT" if school else "SPEED_CAMERA_TRAFFIC_LIGHT"
    if "1" in codes:
        return "SPEED_CAMERA_SCHOOL_ZONE" if school else "SPEED_CAMERA_FIX"
    return None


def select_cameras(rows: Iterable[dict], bounds: Tuple[float, float, float, float]) -> List[Camera]:
    min_lat, min_lon, max_lat, max_lon = bounds
    out = []
    for r in rows:
        kind = camera_type(r)
        try:
            lat, lon = float(r["위도"]), float(r["경도"])
        except ValueError:
            continue
        # No road name: it could never be told apart from an unnamed road the car is on.
        if kind and r["도로노선명"].strip() and min_lat <= lat <= max_lat and min_lon <= lon <= max_lon:
            # 관리번호 is only unique within its provider; the id must not contain the app line's separators.
            camera_id = re.sub(r"[,/;\s]", "-", f"{r['제공기관코드']}-{r['무인교통단속카메라관리번호']}")
            out.append(Camera(camera_id, lat, lon, r["도로노선명"], kind, int(r["제한속도"]), r["설치장소"]))
    return out


def load_cameras(path: str) -> List[Camera]:
    with open(path, encoding="utf-8") as f:
        return [Camera(r["id"], float(r["lat"]), float(r["lon"]), r["road"], r["type"], int(r["limit_kph"]), r["place"])
                for r in csv.DictReader(f)]


def _offset_m(lat: float, lon: float, to_lat: float, to_lon: float) -> Tuple[float, float]:
    """(east, north) metres; equirectangular is plenty within a few km."""
    north = (to_lat - lat) * METERS_PER_DEG_LAT
    east = (to_lon - lon) * METERS_PER_DEG_LAT * math.cos(math.radians((lat + to_lat) / 2))
    return east, north


def angle_diff(a: float, b: float) -> float:
    """b − a in degrees, in (−180, 180]."""
    return (b - a + 180.0) % 360.0 - 180.0


def camera_alerts(cameras: Iterable[Camera], lat: float, lon: float, heading: float, road: str,
                  kph: float) -> List[str]:
    """NaviHelper-style alerts for the cameras ahead on the road the car is on, nearest first, each as the app's
    `id/type/limit/remain_m/over_speed`. heading: compass degrees (0 north, 90 east).
    ponytail: the data has no facing, so a camera for the other direction of the same road is announced too."""
    ahead = []
    for c in cameras:
        east, north = _offset_m(lat, lon, c.lat, c.lon)
        distance = math.hypot(east, north)
        bearing = math.degrees(math.atan2(east, north))
        if c.road and c.road == road and distance <= ALERT_M and abs(angle_diff(heading, bearing)) <= AHEAD_DEG:
            ahead.append((round(distance), c))
    ahead.sort(key=lambda item: item[0])
    return [f"{c.id}/{c.type}/{c.limit_kph}/{d}/{'true' if kph > c.limit_kph else 'false'}" for d, c in ahead]


def main(argv: List[str]) -> None:
    if len(argv) == 3 and argv[0] == "roads":
        with open(argv[1], encoding="utf-8") as f:
            kept = roads_only(f.read())
        with open(argv[2], "w", encoding="utf-8") as f:
            f.write(kept)
    elif len(argv) == 4 and argv[0] == "cameras":
        with open(argv[2], encoding="utf-8") as f:
            bounds = osm_bounds(f.read())
        with open(argv[1], encoding="cp949") as f:  # data.go.kr's CSV is in the Korean Windows code page
            cameras = select_cameras(csv.DictReader(f), bounds)
        with open(argv[3], "w", encoding="utf-8", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(CAMERA_FIELDS)
            writer.writerows(cameras)
        print(f"{len(cameras)} speed cameras inside {bounds}")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])

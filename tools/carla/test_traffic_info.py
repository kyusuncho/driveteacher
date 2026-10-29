"""python3 -m unittest test_traffic_info  (no CARLA needed)"""
import unittest
import xml.etree.ElementTree as ET

from traffic_info import (Camera, camera_alerts, camera_type, fill_speed_limits, road_limits, roads_only,
                          select_cameras)

OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" copyright="OpenStreetMap and contributors">
 <bounds minlat="37.495" minlon="127.024" maxlat="37.503" maxlon="127.040"/>
 <node id="1" lat="37.4981" lon="127.0280"><tag k="highway" v="traffic_signals"/></node>
 <node id="2" lat="37.5006" lon="127.0365"/>
 <node id="3" lat="37.4990" lon="127.0300"/>
 <node id="4" lat="37.4991" lon="127.0301"/>
 <way id="10" user="mapper" uid="7" changeset="9" timestamp="2024-01-01T00:00:00Z" version="3"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="name" v="테헤란로"/></way>
 <way id="11"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
 <way id="12"><nd ref="3"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="maxspeed" v="50"/></way>
 <way id="13"><nd ref="4"/><nd ref="3"/><tag k="building" v="yes"/></way>
 <way id="14"><nd ref="4"/><nd ref="3"/><tag k="railway" v="subway"/><tag k="maxspeed" v="90"/></way>
 <relation id="20"><member type="way" ref="10" role=""/></relation>
</osm>"""


def ways(osm: str) -> dict:
    root = ET.fromstring(osm)
    return {w.get("id"): {t.get("k"): t.get("v") for t in w.iter("tag")} for w in root.iter("way")}


def row(**fields: str) -> dict:
    base = {"단속구분": "1", "제한속도": "50", "단속구간위치구분": "", "보호구역구분": "99", "위도": "37.5",
            "경도": "127.03", "제공기관코드": "1320000", "무인교통단속카메라관리번호": "7", "도로노선명": "테헤란로",
            "설치장소": "역삼역교차로"}
    return {**base, **fields}


class MapTest(unittest.TestCase):
    def test_roads_without_a_limit_get_the_korean_urban_one(self):
        filled = ways(fill_speed_limits(OSM))
        self.assertEqual("50", filled["10"]["maxspeed"])  # 안전속도 5030: main roads
        self.assertEqual("30", filled["11"]["maxspeed"])  # ...and side streets
        self.assertEqual("50", filled["12"]["maxspeed"], "a tagged limit is kept")
        self.assertNotIn("maxspeed", filled["13"], "not a road")

    def test_the_repo_copy_keeps_only_drivable_roads_and_their_nodes(self):
        kept = ET.fromstring(roads_only(OSM))
        self.assertEqual({"10", "11", "12"}, {w.get("id") for w in kept.iter("way")})
        self.assertEqual({"1", "2", "3"}, {n.get("id") for n in kept.iter("node")})
        self.assertEqual([], list(kept.iter("relation")))
        self.assertIsNotNone(kept.find("bounds"))
        self.assertEqual("OpenStreetMap and contributors", kept.get("copyright"))
        self.assertEqual({"id": "10"}, next(kept.iter("way")).attrib, "no edit history")

    def test_road_limit_is_its_fastest_lane_outside_junctions(self):
        xodr = """<OpenDRIVE>
          <road name="테헤란로" length="10" id="7" junction="-1"><lanes><laneSection s="0">
            <right><lane id="-1"><speed sOffset="0" max="13.89"/></lane><lane id="-2"><speed sOffset="0" max="8.33"/></lane></right>
          </laneSection></lanes></road>
          <road name="" length="3" id="8" junction="5"><lanes><laneSection s="0">
            <right><lane id="-1"><speed sOffset="0" max="5.00"/></lane></right></laneSection></lanes></road>
        </OpenDRIVE>"""
        self.assertEqual({7: ("테헤란로", 50)}, road_limits(xodr))


class CameraTest(unittest.TestCase):
    def test_codes_become_navihelper_camera_types(self):
        self.assertEqual("SPEED_CAMERA_FIX", camera_type(row(단속구분="1")))
        self.assertEqual("SPEED_CAMERA_TRAFFIC_LIGHT", camera_type(row(단속구분="2")))
        self.assertEqual("SPEED_CAMERA_TRAFFIC_LIGHT", camera_type(row(단속구분="01+02")))
        self.assertEqual("SPEED_CAMERA_SCHOOL_ZONE", camera_type(row(단속구분="01", 보호구역구분="2")))
        self.assertEqual("SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT", camera_type(row(단속구분="2", 보호구역구분="02")))
        self.assertEqual("SPEED_CAMERA_SECTION_START", camera_type(row(단속구간위치구분="1")))
        self.assertEqual("SPEED_CAMERA_SECTION_END", camera_type(row(단속구간위치구분="02")))

    def test_cameras_that_do_not_enforce_speed_are_left_out(self):
        self.assertIsNone(camera_type(row(단속구분="4", 제한속도="0")), "parking")
        self.assertIsNone(camera_type(row(단속구분="2", 제한속도="0")), "no limit given")
        self.assertIsNone(camera_type(row(단속구분="99")), "meaning unknown")
        self.assertIsNone(camera_type(row(단속구분="3")), "bus lane and other passage rules")

    def test_only_cameras_inside_the_map_are_kept_with_a_line_safe_id(self):
        rows = [row(), row(위도="37.6"), row(단속구분="4", 제한속도="0"), row(무인교통단속카메라관리번호="A/1,2;3"),
                row(도로노선명=" ")]
        got = select_cameras(rows, (37.495, 127.024, 37.503, 127.040))
        self.assertEqual(["1320000-7", "1320000-A-1-2-3"], [c.id for c in got])
        self.assertEqual(Camera("1320000-7", 37.5, 127.03, "테헤란로", "SPEED_CAMERA_FIX", 50, "역삼역교차로"), got[0])


class AlertTest(unittest.TestCase):
    # On 테헤란로 at 강남역 heading for 역삼역 (bearing ~70 degrees).
    HERE = (37.4981, 127.0280)
    HEADING = 70.0

    def alerts(self, cameras: list, road: str = "테헤란로", kph: float = 40.0) -> list:
        return camera_alerts(cameras, *self.HERE, self.HEADING, road, kph)

    def test_a_camera_ahead_on_this_road_is_announced_nearest_first(self):
        far = Camera("a", 37.5006, 127.0365, "테헤란로", "SPEED_CAMERA_TRAFFIC_LIGHT", 50, "역삼역")
        near = Camera("b", 37.4990, 127.0310, "테헤란로", "SPEED_CAMERA_FIX", 30, "")
        self.assertEqual([], self.alerts([far]), "800 m: too far yet")
        got = self.alerts([far, near, Camera("c", 37.4990, 127.0315, "테헤란로", "SPEED_CAMERA_FIX", 50, "")])
        self.assertEqual(["b/SPEED_CAMERA_FIX/30/283/true", "c/SPEED_CAMERA_FIX/50/325/false"], got)

    def test_cameras_behind_or_on_another_road_are_not(self):
        behind = Camera("a", 37.4975, 127.0260, "테헤란로", "SPEED_CAMERA_FIX", 50, "")
        other_road = Camera("b", 37.4990, 127.0310, "강남대로", "SPEED_CAMERA_FIX", 50, "")
        self.assertEqual([], self.alerts([behind, other_road]))

    def test_an_unnamed_road_matches_no_camera(self):
        self.assertEqual([], self.alerts([Camera("b", 37.4990, 127.0310, "", "SPEED_CAMERA_FIX", 30, "")], road=""))

    def test_not_over_at_exactly_the_limit(self):
        near = Camera("b", 37.4990, 127.0310, "테헤란로", "SPEED_CAMERA_FIX", 30, "")
        self.assertEqual(["b/SPEED_CAMERA_FIX/30/283/false"], self.alerts([near], kph=30.0))


if __name__ == "__main__":
    unittest.main()

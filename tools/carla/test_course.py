"""docker compose run --rm --no-deps -T client python -m unittest test_course  (carla's map tools; no server)

Each course on the daechi map turns where 도로교통공단's guide video says, at the junctions the course sketch names.
"""
import math
import unittest

import carla

import bridge
import course
import driver

# Junction middles (OSM traffic-signal nodes; 아시아선수촌 and 종합운동장 are where 백제고분로 meets 도곡로 and 올림픽로).
HWIMUN = (37.5052, 127.0651)  # 휘문고교
DAECHI_WOOSUNG = (37.5010, 127.0675)  # 대치우성아파트
HAKYEOUL = (37.4965, 127.0699)  # 학여울역
ILWON_TUNNEL = (37.4861, 127.0755)  # 일원터널
ASIA = (37.5068, 127.0795)  # 아시아선수촌
SPORTS_COMPLEX = (37.5115, 127.0783)  # 종합운동장
JAMSIL_SAENAE = (37.5116, 127.0855)  # 잠실새내역 방향
TEHERAN_114 = (37.5098, 127.0665)  # 테헤란로114길's north end at 테헤란로

# The voice script's turns between leaving the site's yard and turning back into it. The script also says 좌회전 at
# the rotary just past the gate (A, C, D); OSM has no rotary there, so the course only bends.
EXPECTED = {
    "A": [("left", HWIMUN), ("uturn", ILWON_TUNNEL), ("right", HWIMUN)],
    "B": [("right", TEHERAN_114), ("uturn", JAMSIL_SAENAE), ("left", SPORTS_COMPLEX), ("right", ASIA),
          ("right", DAECHI_WOOSUNG), ("right", HWIMUN)],
    "C": [("left", HWIMUN), ("left", DAECHI_WOOSUNG), ("left", ASIA), ("uturn", SPORTS_COMPLEX), ("right", ASIA),
          ("right", DAECHI_WOOSUNG), ("right", HWIMUN)],
    "D": [("left", HWIMUN), ("uturn", HAKYEOUL), ("right", DAECHI_WOOSUNG), ("uturn", ASIA), ("right", DAECHI_WOOSUNG),
          ("right", HWIMUN)],
}
NEAR_M = 80.0


def metres(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot((a[0] - b[0]) * 111_320, (a[1] - b[1]) * 111_320 * math.cos(math.radians(a[0])))


class CourseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.map = carla.Map("daechi", bridge.osm_to_xodr("daechi"))

    def test_each_course_turns_where_the_guide_video_does(self) -> None:
        for name, expected in EXPECTED.items():
            with self.subTest(course=name):
                points = course.route(self.map, course.COURSES[name])
                got = course.turns(points, self.map)
                self.assertEqual([kind for kind, _ in expected], [t.kind for t in got])
                for (kind, at), turn in zip(expected, got):
                    self.assertLess(metres(at, (turn.lat, turn.lon)), NEAR_M, f"{kind} at {turn}")

    def test_lane_changes_stay_out_of_the_apps_turn_windows(self) -> None:
        """A lane change's blinker ends (it is in the new lane) at least CLEAR_BEFORE_M before the next junction."""
        for name in EXPECTED:
            with self.subTest(course=name):
                points = course.route(self.map, course.COURSES[name])
                for k, p in enumerate(points):
                    if not p.change:
                        continue
                    ahead = 0.0
                    for a, b in zip(points[k:], points[k + 1:]):
                        if a.junction:
                            break
                        ahead += a.wp.transform.location.distance(b.wp.transform.location)
                    self.assertGreaterEqual(ahead, course.CLEAR_BEFORE_M - 1, f"lane change at point {k}")

    def test_lane_change_blinkers_start_clear_of_the_turn_before(self) -> None:
        """The driver blinks driver.CHANGE_BLINK_M before a lane change: by then the car is well past the app's 30 m
        after the turn before (item 33), or after pulling out (item 13)."""
        for name in EXPECTED:
            with self.subTest(course=name):
                points = course.route(self.map, course.COURSES[name])
                since = 0.0
                for a, b in zip(points, points[1:]):
                    since = 0.0 if a.junction else since + a.wp.transform.location.distance(b.wp.transform.location)
                    if b.change:
                        self.assertGreaterEqual(since - driver.CHANGE_BLINK_M, 35.0, f"lane change into {b.wp.road_id}")

    def test_the_driver_sees_through_each_junction_when_it_starts_to_signal(self) -> None:
        """It signals BLINK_BEFORE_M before a junction, for the turn it reads from its plan through to the far side."""
        for name in EXPECTED:
            with self.subTest(course=name):
                points = course.route(self.map, course.COURSES[name])
                run = 0.0
                for a, b in zip(points, points[1:]):
                    run = run + a.wp.transform.location.distance(b.wp.transform.location) if a.junction else 0.0
                    self.assertLess(run + driver.BLINK_BEFORE_M, driver.COURSE_PLAN_M - 2 * driver.STEP_M)

    def test_a_course_starts_at_the_gate_and_ends_back_there(self) -> None:
        for name in EXPECTED:
            with self.subTest(course=name):
                points = course.route(self.map, course.COURSES[name])
                for p, at in ((points[0], course.COURSES[name][0]), (points[-1], course.COURSES[name][-1])):
                    g = self.map.transform_to_geolocation(p.wp.transform.location)
                    self.assertLess(metres(at[:2], (g.latitude, g.longitude)), 10.0)


if __name__ == "__main__":
    unittest.main()

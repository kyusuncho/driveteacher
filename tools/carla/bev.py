"""Bird's-eye-view frames of the CARLA world.

The server runs with -nullrhi (no rendering), so there is no camera image. Instead we draw the road map,
vehicles and traffic-light stop lines from CARLA's own state, which is exactly what the bridge sees.
"""
from __future__ import annotations

import math

import carla
from PIL import Image, ImageDraw, ImageFont

PX_PER_M = 8.0
SIZE = 720  # square frame, ego at the center, north up
MARGIN_M = 60.0
AREA_MARGIN_M = 100.0  # with an area: the roads this far around it (a frame shows 45 m each side)
BACKGROUND = (24, 26, 31)
ROAD = (64, 68, 76)
EGO = (250, 250, 250)  # not red: red/yellow/green are the traffic-light stop lines
TRAFFIC = (96, 165, 250)
TEXT = (235, 235, 235)
DIM = (150, 150, 150)
LIGHT_COLORS = {
    carla.TrafficLightState.Red: (239, 68, 68),
    carla.TrafficLightState.Yellow: (250, 204, 21),
    carla.TrafficLightState.Green: (34, 197, 94),
}
FOOTER = "drawn from CARLA state (no rendering)   white = ego   blue = traffic   bars = traffic-light stop lines"


class BirdsEye:
    def __init__(self, world: carla.World, area: list[carla.Location] | None = None) -> None:
        """[area]: draw only the roads around these places. A big map's whole base image does not fit in memory
        (대치동's 2.5×3 km would be 1.4 GB)."""
        self.world = world
        waypoints = world.get_map().generate_waypoints(1.0)
        if area:
            lo_x, hi_x = min(a.x for a in area) - AREA_MARGIN_M, max(a.x for a in area) + AREA_MARGIN_M
            lo_y, hi_y = min(a.y for a in area) - AREA_MARGIN_M, max(a.y for a in area) + AREA_MARGIN_M
            waypoints = [w for w in waypoints
                         if lo_x <= w.transform.location.x <= hi_x and lo_y <= w.transform.location.y <= hi_y]
        xs = [w.transform.location.x for w in waypoints]
        ys = [w.transform.location.y for w in waypoints]
        self.x0, self.y0 = min(xs) - MARGIN_M, min(ys) - MARGIN_M
        size = (int((max(xs) + MARGIN_M - self.x0) * PX_PER_M), int((max(ys) + MARGIN_M - self.y0) * PX_PER_M))
        self.base = Image.new("RGB", size, BACKGROUND)
        draw = ImageDraw.Draw(self.base)
        for w in waypoints:  # overlapping lane-width discs every 1 m form the road surface
            x, y = self._px(w.transform.location.x, w.transform.location.y)
            r = w.lane_width * PX_PER_M / 2
            draw.ellipse((x - r, y - r, x + r, y + r), fill=ROAD)
        self.stop_lines = [(light, light.get_stop_waypoints()) for light in world.get_actors().filter("traffic.traffic_light")]
        self.font = ImageFont.load_default(size=20)
        self.small = ImageFont.load_default(size=14)

    def _px(self, x: float, y: float) -> tuple[float, float]:
        # CARLA: +x east, +y south -> image right / down, i.e. north up.
        return (x - self.x0) * PX_PER_M, (y - self.y0) * PX_PER_M

    def render(self, ego: carla.Actor, hud: list[str]) -> Image.Image:
        cx, cy = self._px(ego.get_location().x, ego.get_location().y)
        left, top = int(cx - SIZE / 2), int(cy - SIZE / 2)
        frame = self.base.crop((left, top, left + SIZE, top + SIZE))
        draw = ImageDraw.Draw(frame)

        def to_frame(x: float, y: float) -> tuple[float, float]:
            px, py = self._px(x, y)
            return px - left, py - top

        for light, stops in self.stop_lines:
            color = LIGHT_COLORS.get(light.get_state())
            if color is None:
                continue
            for w in stops:  # a bar across the lane at the stop line
                loc, yaw = w.transform.location, math.radians(w.transform.rotation.yaw)
                dx, dy = -math.sin(yaw) * w.lane_width / 2, math.cos(yaw) * w.lane_width / 2
                draw.line([to_frame(loc.x - dx, loc.y - dy), to_frame(loc.x + dx, loc.y + dy)], fill=color, width=6)

        for vehicle in self.world.get_actors().filter("vehicle.*"):
            color = EGO if vehicle.id == ego.id else TRAFFIC
            draw.polygon([to_frame(x, y) for x, y in _corners(vehicle)], fill=color)

        draw.rectangle((10, 10, 400, 22 + 28 * len(hud)), fill=(0, 0, 0))
        for i, line in enumerate(hud):
            draw.text((22, 18 + 28 * i), line, font=self.font, fill=TEXT)
        draw.text((10, SIZE - 24), FOOTER, font=self.small, fill=DIM)
        return frame


def _corners(vehicle: carla.Actor) -> list[tuple[float, float]]:
    t, box = vehicle.get_transform(), vehicle.bounding_box
    yaw = math.radians(t.rotation.yaw)
    c, s = math.cos(yaw), math.sin(yaw)
    cx = t.location.x + box.location.x * c - box.location.y * s
    cy = t.location.y + box.location.x * s + box.location.y * c
    ex, ey = box.extent.x, box.extent.y
    return [(cx + dx * c - dy * s, cy + dx * s + dy * c) for dx, dy in ((ex, ey), (ex, -ey), (-ex, -ey), (-ex, ey))]

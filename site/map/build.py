"""Map layer for the landing page: course C (route.py's courseC.json) over the street network around it.

Streets: an Overpass export (out geom) of the highway and water ways in 37.4955,127.0575,37.5150,127.0880.
Data © OpenStreetMap contributors, ODbL. Writes compact SVG path strings so the page draws without a map library.
Usage: python3 build.py courseC.json streets.json out.json
"""
import json, math, sys

LAT0 = 37.5
def xy(lat, lon): return ((lon - 127.06) * 111320 * math.cos(math.radians(LAT0)), (lat - LAT0) * 110540)

course = json.load(open(sys.argv[1]))
streets = json.load(open(sys.argv[2]))["elements"]
route = [(x, -y) for x, y in course["route"]]  # SVG y grows downward
MARGIN = 420
xs, ys = [p[0] for p in route], [p[1] for p in route]
box = (min(xs) - MARGIN, min(ys) - MARGIN, max(xs) + MARGIN, max(ys) + MARGIN)
inside = lambda p: box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]

MAJOR = {"motorway", "trunk", "primary", "secondary", "tertiary"}
def layer(tags):
    if tags.get("waterway") or tags.get("natural") == "water": return "water"
    hw = tags.get("highway", "")
    if hw in MAJOR: return "major"
    if hw.endswith("_link"): return "link"
    if hw == "service": return "service"
    return "minor"

def d_of(pts):
    out, last = [], None
    for x, y in pts:
        if last and math.dist(last, (x, y)) < 3: continue
        out.append(f"{'M' if not out else 'L'}{x:.0f} {y:.0f}"); last = (x, y)
    return "".join(out) if len(out) > 1 else ""

layers, names = {}, {}
for el in streets:
    if el.get("type") != "way" or "geometry" not in el: continue
    pts = [(x, -y) for x, y in (xy(g["lat"], g["lon"]) for g in el["geometry"])]
    if not any(inside(p) for p in pts): continue
    tags = el.get("tags", {}); kind = layer(tags)
    layers.setdefault(kind, []).append(d_of(pts))
    name = tags.get("name")
    if kind == "major" and name:
        L = sum(math.dist(a, b) for a, b in zip(pts, pts[1:]))
        if L > names.get(name, (0,))[0]: names[name] = (L, pts)

def along(s):
    acc = 0
    for a, b in zip(route, route[1:]):
        seg = math.dist(a, b)
        if acc + seg >= s:
            t = (s - acc) / seg; return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        acc += seg
    return route[-1]

# The seven turns the app scores on course C (course.py: 휘문고교 left, 대치우성아파트 left, 아시아선수촌 left,
# U-turn at 종합운동장, then right, right, right home); route.py also reports the gate road's bends, not scored.
SCORED = [(527, "휘문고교 사거리", "좌회전"), (1036, "대치우성아파트", "좌회전"), (2326, "아시아선수촌", "좌회전"),
          (2667, "종합운동장", "유턴"), (3017, "아시아선수촌", "우회전"), (4249, "대치우성아파트", "우회전"),
          (4760, "휘문고교 사거리", "우회전")]
turns = []
for s, place, move in SCORED:
    x, y = along(s); turns.append({"s": s, "x": round(x), "y": round(y), "place": place, "move": move})

labels = []
for name in ("영동대로", "도곡로", "백제고분로", "테헤란로", "삼성로", "남부순환로", "올림픽로"):
    if name not in names: continue
    pts = names[name][1]; mid = len(pts) // 2; a, b = pts[max(0, mid - 1)], pts[min(len(pts) - 1, mid + 1)]
    ang = math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))
    if ang > 90: ang -= 180
    if ang < -90: ang += 180
    if inside(pts[mid]): labels.append({"text": name, "x": round(pts[mid][0]), "y": round(pts[mid][1]), "angle": round(ang)})

length = sum(math.dist(a, b) for a, b in zip(route, route[1:]))
json.dump({"viewBox": [round(v) for v in (box[0], box[1], box[2] - box[0], box[3] - box[1])],
           "layers": {k: "".join(v) for k, v in layers.items()}, "route": d_of(route), "length": round(length),
           "turns": turns, "labels": labels, "gate": {"x": round(route[0][0]), "y": round(route[0][1])}},
          open(sys.argv[3], "w"), ensure_ascii=False, separators=(",", ":"))
print("layers", {k: len(v) for k, v in layers.items()}, "labels", [l["text"] for l in labels], "length", round(length))

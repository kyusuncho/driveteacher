"""Course C over the OSM road graph: snap course.py's waypoints to directed road edges, Dijkstra between them."""
import heapq, json, math, sys, xml.etree.ElementTree as ET

OSM = sys.argv[1]
DRIVE = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
         "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link", "service"}
GATE_S = (37.50830, 127.06703, 162.0); GATE_BACK = (37.50860, 127.06692, 342.0)
C = [GATE_S, (37.50380, 127.06580, 160.0), (37.50180, 127.07000, 69.0), (37.50850, 127.07920, 350.0),
     (37.50950, 127.07885, 170.0), (37.50235, 127.07158, 247.0), (37.50250, 127.06680, 340.0),
     (37.50560, 127.06640, 68.0), GATE_BACK]
LAT0 = 37.5
def xy(lat, lon): return ((lon - 127.06) * 111320 * math.cos(math.radians(LAT0)), (lat - LAT0) * 110540)
def bearing(a, b): return math.degrees(math.atan2(b[0] - a[0], b[1] - a[1])) % 360
def adiff(a, b): return abs((a - b + 180) % 360 - 180)

root = ET.parse(OSM).getroot()
node = {n.get("id"): xy(float(n.get("lat")), float(n.get("lon"))) for n in root.iter("node")}
edges, ways = {}, []
for w in root.iter("way"):
    tags = {t.get("k"): t.get("v") for t in w.iter("tag")}
    hw = tags.get("highway")
    if not hw: continue
    refs = [n.get("ref") for n in w.iter("nd") if n.get("ref") in node]
    ways.append({"hw": hw, "name": tags.get("name", ""), "pts": [node[r] for r in refs]})
    if hw not in DRIVE: continue
    ow = tags.get("oneway", "yes" if tags.get("junction") == "roundabout" or hw.endswith("motorway") else "no")
    for a, b in zip(refs, refs[1:]):
        d = math.dist(node[a], node[b])
        if ow != "-1": edges.setdefault(a, []).append((b, d))
        if ow in ("no", "-1"): edges.setdefault(b, []).append((a, d))

# U-turns: let the route cross to a node of another road within 30 m, at a cost, only where it reverses direction
def snap(lat, lon, hdg):
    p = xy(lat, lon); best = None
    for a, outs in edges.items():
        for b, _ in outs:
            pa, pb = node[a], node[b]
            if adiff(bearing(pa, pb), hdg) > 45: continue
            vx, vy = pb[0] - pa[0], pb[1] - pa[1]; L = vx * vx + vy * vy or 1
            t = max(0, min(1, ((p[0] - pa[0]) * vx + (p[1] - pa[1]) * vy) / L))
            d = math.dist(p, (pa[0] + t * vx, pa[1] + t * vy))
            if best is None or d < best[0]: best = (d, a, b)
    return best

def dijkstra(src, dst, uturn_ok):
    dist, prev, pq = {src: 0}, {}, [(0, src)]
    while pq:
        d, u = heapq.heappop(pq)
        if u == dst: break
        if d > dist[u]: continue
        nbrs = list(edges.get(u, []))
        if uturn_ok:
            nbrs += [(v, 400 + math.dist(node[u], node[v])) for v in edges if v != u and math.dist(node[u], node[v]) < 35]
        for v, w in nbrs:
            if d + w < dist.get(v, 1e18): dist[v] = d + w; prev[v] = u; heapq.heappush(pq, (d + w, v))
    path = [dst]
    while path[-1] != src: path.append(prev[path[-1]])
    return path[::-1]

snaps = [snap(*w) for w in C]
for i, s in enumerate(snaps): print("wp", i, "snap err %.0f m" % s[0], file=sys.stderr)
route = [snaps[0][1]]
for i in range(len(C) - 1):
    a, b = snaps[i][2], snaps[i + 1][1]
    seg = dijkstra(a, b, uturn_ok=(i == 3))
    route += seg + [snaps[i + 1][2]]
pts = [node[r] for r in route]
dedup = [pts[0]] + [q for p, q in zip(pts, pts[1:]) if math.dist(p, q) > 0.5]
length = sum(math.dist(p, q) for p, q in zip(dedup, dedup[1:]))
print("route pts", len(dedup), "length %.0f m" % length, file=sys.stderr)
# turns: heading change > 30 deg measured over 25 m either side of each point, merged within 60 m
def at(i, back):
    j, s = i, 0
    while 0 < j < len(dedup) - 1 and s < 25:
        k = j - 1 if back else j + 1; s += math.dist(dedup[j], dedup[k]); j = k
    return j
turns, cum = [], [0]
for p, q in zip(dedup, dedup[1:]): cum.append(cum[-1] + math.dist(p, q))
for i in range(1, len(dedup) - 1):
    a, b = at(i, True), at(i, False)
    h1, h2 = bearing(dedup[a], dedup[i]), bearing(dedup[i], dedup[b])
    delta = (h2 - h1 + 180) % 360 - 180
    if abs(delta) > 30 and cum[i] > 30 and cum[-1] - cum[i] > 30:
        if turns and cum[i] - turns[-1]["s"] < 60:
            if abs(delta) > abs(turns[-1]["delta"]): turns[-1].update(i=i, s=cum[i], delta=delta)
            continue
        turns.append({"i": i, "s": cum[i], "delta": delta})
for t in turns: print("turn at %.0f m  %+.0f deg" % (t["s"], t["delta"]), file=sys.stderr)
json.dump({"route": [[round(x, 1), round(y, 1)] for x, y in dedup], "turns": [[round(cum[t["i"]], 1), round(t["delta"])] for t in turns],
           "ways": [{"hw": w["hw"], "name": w["name"], "pts": [[round(x, 1), round(y, 1)] for x, y in w["pts"]]} for w in ways]},
          open("courseC.json", "w"))

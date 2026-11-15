// 운전선생 landing page: the hero's live penalty, the course C replay map, the video tabs.
"use strict";

const SVG_NS = "http://www.w3.org/2000/svg";
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

// What each run did on course C (from the app's result screens). appToVideo: the video starts this many
// seconds after the test clock, measured where each penalty appears on screen.
const PENALTY = { plate: "30", name: "진로변경 신호 불이행", reason: "회전하는 동안 방향지시등을 켜지 않았어요.", cost: 7 };
const RUNS = {
  100: { score: 100, missed: [], times: [], drive: "15:10", appToVideo: 0 },
  86: { score: 86, missed: [2, 6], times: ["2:58", "12:01"], drive: "14:45", appToVideo: 24.7 },
  51: { score: 51, missed: [1, 2, 3, 4, 5, 6, 7], times: ["1:34", "3:12", "6:45", "8:01", "8:54", "12:25", "13:55"], drive: "15:19", appToVideo: 17.4 },
};
const VIDEOS = {
  summary: { src: "media/summary.mp4", poster: "media/poster-summary.webp", ratio: "summary" },
  100: { src: "media/run-100.mp4", poster: "media/poster-100.webp" },
  86: { src: "media/run-86.mp4", poster: "media/poster-86.webp" },
  51: { src: "media/run-51.mp4", poster: "media/poster-51.webp" },
};
const CHAPTERS = [
  { at: 6, time: "0:06", name: "100점 주행", note: "C코스 전체, 10배속" },
  { at: 96, time: "1:36", name: "86점 주행의 첫 감점", note: "2번째 회전, 실제 속도" },
  { at: 114, time: "1:54", name: "51점 주행", note: "C코스 전체, 10배속" },
  { at: 205, time: "3:25", name: "세 결과 화면 비교", note: "100점 · 86점 · 51점" },
];
const SEEK_LEAD_S = 4; // start a little before the penalty so the turn is visible

const seconds = (mmss) => mmss.split(":").reduce((acc, part) => acc * 60 + Number(part), 0);

function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
  children.forEach((child) => node.append(child));
  return node;
}
function svg(tag, attrs = {}) {
  const node = document.createElementNS(SVG_NS, tag);
  Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
  return node;
}

/** A pause/play pill for looping motion (WCAG 2.2.2). onChange(paused) runs on every press. */
function pauseButton(name, onChange) {
  const button = document.querySelector(`[data-pause="${name}"]`);
  if (!button) return { paused: () => false };
  const label = button.querySelector("span");
  let paused = false;
  button.addEventListener("click", () => {
    paused = !paused;
    button.setAttribute("aria-pressed", String(paused));
    label.textContent = paused ? "재생" : "멈춤";
    onChange(paused);
  });
  return { paused: () => paused };
}

/* ---------- hero: the plates appear when the app on screen deducts ---------- */
function initHero() {
  const video = document.querySelector(".unit__video");
  const floats = document.querySelectorAll(".float");
  if (!video) return;
  const PENALTY_ON = 6.5; // hero-loop.mp4: the 86 run's first deduction lands here
  const PENALTY_OFF = 11; // ...and the result screen follows
  const show = (on) => floats.forEach((f) => f.classList.toggle("is-on", on));
  if (reduceMotion.matches) {
    video.removeAttribute("autoplay");
    show(true);
    return;
  }
  video.addEventListener("timeupdate", () => show(video.currentTime >= PENALTY_ON && video.currentTime < PENALTY_OFF));
  const play = () => video.play().catch(() => show(true)); // autoplay refused: keep the poster and the plates
  const control = pauseButton("hero", (paused) => (paused ? video.pause() : play()));
  new IntersectionObserver(([entry]) => {
    if (!entry.isIntersecting) video.pause();
    else if (!control.paused()) play();
  }, { threshold: 0.05 }).observe(video);
}

/* ---------- course replay ---------- */
const REPLAY_MS = 15000;
const HOLD_MS = 2800;

async function initReplay() {
  const map = document.querySelector(".map__svg");
  if (!map) return;
  let data;
  try {
    const response = await fetch("media/course-c.json");
    if (!response.ok) throw new Error(`course-c.json ${response.status}`);
    data = await response.json();
  } catch (error) {
    console.error("Course map failed to load", error);
    map.setAttribute("viewBox", "0 0 1000 400");
    const text = svg("text", { x: 60, y: 210, class: "map__error" });
    text.textContent = "지도를 불러오지 못했습니다. 새로고침해 주세요.";
    map.append(text);
    return;
  }
  const scene = drawMap(map, data);
  const replay = createReplay(scene, data);
  const status = document.querySelector('[data-readout="status"]');
  const control = pauseButton("replay", (paused) => (paused ? replay.pause() : replay.resume()));
  const announce = (id) => {
    const run = RUNS[id];
    const missed = run.missed.length ? `${run.missed.join(", ")}번째 회전에서 방향지시등을 켜지 않아 감점 ${run.missed.length}건` : "일곱 번의 회전 모두 신호, 감점 없음";
    status.textContent = `${run.score}점 주행: ${missed}. ${run.score >= 70 ? "합격" : "불합격"}.`;
  };
  document.querySelectorAll(".run").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll(".run").forEach((b) => b.setAttribute("aria-pressed", String(b === button)));
      replay.select(button.dataset.run, control.paused());
      announce(button.dataset.run);
    });
  });
  replay.select("100", false);
  const visible = { map: false };
  new IntersectionObserver(([entry]) => {
    visible.map = entry.isIntersecting;
    if (!visible.map) replay.pause(); else if (!control.paused()) replay.resume();
  }, { threshold: 0.2 }).observe(map);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) replay.pause(); else if (visible.map && !control.paused()) replay.resume();
  });
}

function drawMap(map, data) {
  const [x, y, w, h] = data.viewBox;
  map.setAttribute("viewBox", `${x} ${y} ${w} ${h}`);
  ["water", "service", "minor", "link", "major"].forEach((kind) => {
    if (data.layers[kind]) map.append(svg("path", { d: data.layers[kind], class: `lyr lyr--${kind}` }));
  });
  data.labels.forEach((label) => {
    const text = svg("text", { x: label.x, y: label.y, class: "map-label", transform: `rotate(${label.angle} ${label.x} ${label.y})`, "text-anchor": "middle" });
    text.textContent = label.text;
    map.append(text);
  });
  map.append(svg("path", { d: data.route, class: "route-base" }));
  const lit = svg("path", { d: data.route, class: "route-lit" });
  map.append(lit);

  const gate = svg("g", { class: "gate" });
  gate.append(svg("circle", { cx: data.gate.x, cy: data.gate.y, r: 14 }));
  gate.append(svg("rect", { x: data.gate.x - 250, y: data.gate.y - 30, width: 220, height: 60, rx: 12 }));
  const gateText = svg("text", { x: data.gate.x - 140, y: data.gate.y + 12, "text-anchor": "middle" });
  gateText.textContent = "시험장";
  gate.append(gateText);
  map.append(gate);

  // turns 1/7, 2/6 and 3/5 share a junction: pull each pair apart so both numbers read
  const spots = data.turns.map((t) => ({ x: t.x, y: t.y }));
  spots.forEach((a, i) => spots.forEach((b, j) => {
    if (j <= i || Math.hypot(a.x - b.x, a.y - b.y) > 90) return;
    const dx = b.x - a.x || 1, dy = b.y - a.y, len = Math.hypot(dx, dy), push = (90 - len) / 2 + 12;
    a.x -= (dx / len) * push; a.y -= (dy / len) * push; b.x += (dx / len) * push; b.y += (dy / len) * push;
  }));
  const turns = data.turns.map((t, i) => {
    const g = svg("g", { class: "turn" });
    g.append(svg("circle", { cx: spots[i].x, cy: spots[i].y, r: 30 }));
    const n = svg("text", { x: spots[i].x, y: spots[i].y + 2 });
    n.textContent = String(i + 1);
    g.append(n);
    map.append(g);
    return g;
  });
  const callouts = data.turns.map((t, i) => {
    const g = svg("g", { class: "callout" });
    const cx = spots[i].x + 44, cy = spots[i].y - 70;
    g.append(svg("rect", { x: cx, y: cy - 30, width: 180, height: 60, rx: 12 }));
    g.append(svg("rect", { class: "callout__plate", x: cx + 8, y: cy - 22, width: 64, height: 44, rx: 8 }));
    const plate = svg("text", { x: cx + 40, y: cy + 3, "text-anchor": "middle" });
    plate.textContent = PENALTY.plate;
    const cost = svg("text", { x: cx + 128, y: cy + 3, "text-anchor": "middle" });
    cost.textContent = `−${PENALTY.cost}`;
    g.append(plate, cost);
    map.append(g);
    return g;
  });
  const halo = svg("circle", { r: 46, class: "car-halo" });
  const car = svg("circle", { r: 17, class: "car" });
  map.append(halo, car);
  return { lit, turns, callouts, car, halo };
}

function createReplay(scene, data) {
  const total = scene.lit.getTotalLength();
  const turnAt = data.turns.map((t) => (t.s / data.length) * total);
  scene.lit.style.strokeDasharray = `${total} ${total}`;
  const readout = document.querySelector(".readout");
  const fields = Object.fromEntries(["score", "count", "turn"].map((name) => [name, document.querySelector(`[data-readout="${name}"]`)]));
  const write = (name, value) => { if (fields[name].textContent !== String(value)) fields[name].textContent = String(value); };
  let run = RUNS[100];
  let start = 0, raf = 0, running = false, pausedAt = 0;

  function render(along) {
    const point = scene.lit.getPointAtLength(along);
    scene.car.setAttribute("cx", point.x); scene.car.setAttribute("cy", point.y);
    scene.halo.setAttribute("cx", point.x); scene.halo.setAttribute("cy", point.y);
    scene.lit.style.strokeDashoffset = String(total - along);
    let score = 100, count = 0, passed = 0;
    turnAt.forEach((at, i) => {
      const done = along >= at;
      const missed = run.missed.includes(i + 1);
      if (done) { passed += 1; if (missed) { score -= PENALTY.cost; count += 1; } }
      scene.turns[i].classList.toggle("is-ok", done && !missed);
      scene.turns[i].classList.toggle("is-miss", done && missed);
      scene.callouts[i].classList.toggle("is-on", done && missed && along - at < total * 0.12);
    });
    write("score", score);
    write("count", count);
    write("turn", passed);
    readout.classList.toggle("is-failing", score < 70);
  }
  function frame(now) {
    const t = now - start;
    const progress = Math.min(t / REPLAY_MS, 1);
    render(progress * total);
    if (t > REPLAY_MS + HOLD_MS) start = now;
    raf = requestAnimationFrame(frame);
  }
  return {
    select(id, paused) {
      run = RUNS[id];
      cancelAnimationFrame(raf);
      if (reduceMotion.matches || paused) { render(total); running = false; pausedAt = paused ? performance.now() : 0; start = pausedAt - REPLAY_MS; return; }
      start = performance.now(); pausedAt = 0; running = true;
      raf = requestAnimationFrame(frame);
    },
    pause() {
      if (!running) return;
      running = false; pausedAt = performance.now(); cancelAnimationFrame(raf);
    },
    resume() {
      if (running || reduceMotion.matches || !pausedAt) return;
      start += performance.now() - pausedAt; pausedAt = 0; running = true;
      raf = requestAnimationFrame(frame);
    },
  };
}

/* ---------- videos ---------- */
function initVideos() {
  const video = document.querySelector(".player__video");
  const tabs = [...document.querySelectorAll('.tabs [role="tab"]')];
  const panel = document.querySelector(".player");
  const rows = document.querySelector('[data-list="rows"]');
  const title = document.querySelector('[data-list="title"]');
  const count = document.querySelector('[data-list="count"]');
  if (!video || !rows) return;

  const seek = (at) => {
    const go = () => { video.currentTime = Math.max(0, at); video.play().catch(() => {}); };
    if (video.readyState >= 1) go(); else { video.addEventListener("loadedmetadata", go, { once: true }); video.preload = "metadata"; video.load(); }
    video.scrollIntoView({ behavior: reduceMotion.matches ? "auto" : "smooth", block: "center" });
  };
  const playIcon = () => {
    const icon = svg("svg", { viewBox: "0 0 24 24", class: "prow__play", "aria-hidden": "true" });
    icon.append(svg("path", { d: "M8 5.5v13l10-6.5z" }));
    return icon;
  };
  function renderRows(id) {
    rows.replaceChildren();
    if (id === "summary") {
      title.textContent = "요약 영상 구성"; count.textContent = `${CHAPTERS.length}개 장면`;
      CHAPTERS.forEach((c) => {
        const button = el("button", { type: "button", class: "prow prow--chapter", "aria-label": `${c.time} ${c.name}부터 재생` }, [
          el("span", { class: "prow__time" }, [c.time]),
          el("span", { class: "prow__name" }, [el("strong", {}, [c.name]), el("span", {}, [c.note])]),
          playIcon(),
        ]);
        button.addEventListener("click", () => seek(c.at));
        rows.append(el("li", {}, [button]));
      });
      return;
    }
    const run = RUNS[id];
    title.textContent = "감점 내역"; count.textContent = `${run.times.length}건`;
    if (!run.times.length) {
      rows.append(el("li", { class: "empty" }, [el("strong", {}, ["감점 없음"]), el("span", {}, [`일곱 번의 회전 모두 방향지시등을 켰어요. 주행 시간 ${run.drive}.`])]));
      return;
    }
    run.times.forEach((time, i) => {
      const turn = run.missed[i];
      const at = seconds(time) - run.appToVideo - SEEK_LEAD_S;
      const button = el("button", { type: "button", class: "prow", "aria-label": `${time} ${PENALTY.name}, ${turn}번째 회전, 그 장면 재생` }, [
        el("span", { class: "prow__time" }, [time]),
        el("span", { class: "nplate" }, [PENALTY.plate]),
        el("span", { class: "prow__name" }, [el("strong", {}, [PENALTY.name]), el("span", {}, [`${turn}번째 회전 · ${PENALTY.reason}`])]),
        el("span", { class: "prow__cost" }, [`−${PENALTY.cost}`]),
      ]);
      button.addEventListener("click", () => seek(at));
      rows.append(el("li", {}, [button]));
    });
  }
  function select(tab, focus) {
    tabs.forEach((t) => { const on = t === tab; t.setAttribute("aria-selected", String(on)); t.tabIndex = on ? 0 : -1; });
    if (focus) tab.focus();
    const id = tab.dataset.video;
    const source = VIDEOS[id];
    video.pause();
    video.preload = "none";
    video.poster = source.poster;
    video.src = source.src;
    if (source.ratio) video.dataset.ratio = source.ratio; else delete video.dataset.ratio;
    panel.setAttribute("aria-labelledby", tab.id);
    renderRows(id);
  }
  tabs.forEach((tab, i) => {
    tab.addEventListener("click", () => select(tab, false));
    tab.addEventListener("keydown", (event) => {
      const step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
      if (event.key === "Home" || event.key === "End") { event.preventDefault(); select(tabs[event.key === "Home" ? 0 : tabs.length - 1], true); }
      if (!step) return;
      event.preventDefault();
      select(tabs[(i + step + tabs.length) % tabs.length], true);
    });
  });
  video.dataset.ratio = "summary";
  renderRows("summary");
}

initHero();
initReplay();
initVideos();

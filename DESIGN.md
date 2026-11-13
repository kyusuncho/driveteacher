---
name: 운전선생
description: 도로주행 시험 채점을 도로표지판 한 장에 쓰는 AAOS 헤드유닛 앱
colors:
  concrete: "#E4E6E2"
  concrete-ink: "#15191A"
  concrete-ink-muted: "#4A524F"
  asphalt: "#101312"
  asphalt-ink: "#E3E7E4"
  asphalt-ink-muted: "#9BA49F"
  road: "#2B2F2F"
  road-night: "#1B1E1E"
  road-paint: "#F2F2EE"
  road-paint-night: "#B9BDB8"
  gantry-steel: "#6E7673"
  gantry-steel-night: "#3A403E"
  guide-green: "#0B6B47"
  guide-green-night: "#075438"
  route-blue: "#0A4FA0"
  route-blue-night: "#093E7E"
  sign-white: "#FFFFFF"
  sign-white-night: "#E8ECE9"
  sign-white-muted: "#FFFFFFC8"
  sign-white-muted-night: "#E8ECE9B8"
  plate-white: "#FFFFFF"
  plate-white-night: "#D9DDD9"
  plate-ink: "#121414"
  plate-ink-night: "#101212"
  caution-yellow: "#F6C200"
  caution-yellow-night: "#D8AA00"
  prohibition-red: "#D2232A"
  prohibition-red-night: "#C2262C"
  work-orange: "#F08C00"
  work-orange-night: "#CF7A00"
  caution-panel-night: "#231D06"
  caution-panel-ink-night: "#EACB5A"
  prohibition-panel-night: "#240D0E"
  prohibition-panel-ink-night: "#ECE4E3"
typography:
  display:
    fontFamily: "Overpass, Gothic A1"
    fontSize: "40-700sp autosize"
    fontWeight: 800
    letterSpacing: "-0.04em"
    fontFeature: "tnum"
  title:
    fontFamily: "Gothic A1"
    fontSize: "56sp"
    fontWeight: 800
    lineHeight: 1.18
  headline:
    fontFamily: "Gothic A1"
    fontSize: "44sp"
    fontWeight: 800
    lineHeight: 1.18
  line:
    fontFamily: "Gothic A1"
    fontSize: "32sp"
    fontWeight: 800
    lineHeight: 1.18
  body:
    fontFamily: "Gothic A1"
    fontSize: "26sp"
    fontWeight: 500
    lineHeight: 1.18
  label:
    fontFamily: "Gothic A1"
    fontSize: "24sp"
    fontWeight: 700
    lineHeight: 1.18
  figure:
    fontFamily: "Overpass, Gothic A1"
    fontSize: "32sp"
    fontWeight: 800
    fontFeature: "tnum"
  figure-light:
    fontFamily: "Overpass, Gothic A1"
    fontSize: "24sp"
    fontWeight: 600
    fontFeature: "tnum"
rounded:
  plate: "10dp"
  button: "14dp"
  sign: "22dp"
spacing:
  xs: "8dp"
  s: "16dp"
  m: "24dp"
  l: "40dp"
  xl: "64dp"
  touch: "96dp"
components:
  sign-guide:
    backgroundColor: "{colors.guide-green}"
    textColor: "{colors.sign-white}"
    rounded: "{rounded.sign}"
    padding: "40dp"
  sign-route:
    backgroundColor: "{colors.route-blue}"
    textColor: "{colors.sign-white}"
    rounded: "{rounded.sign}"
    padding: "40dp"
  sign-caution:
    backgroundColor: "{colors.caution-yellow}"
    textColor: "{colors.plate-ink}"
    rounded: "{rounded.sign}"
  sign-caution-night:
    backgroundColor: "{colors.caution-panel-night}"
    textColor: "{colors.caution-panel-ink-night}"
    rounded: "{rounded.sign}"
  sign-prohibition:
    backgroundColor: "{colors.sign-white}"
    textColor: "{colors.plate-ink}"
    rounded: "{rounded.sign}"
  sign-prohibition-night:
    backgroundColor: "{colors.prohibition-panel-night}"
    textColor: "{colors.prohibition-panel-ink-night}"
    rounded: "{rounded.sign}"
  plate-auxiliary:
    backgroundColor: "{colors.plate-white}"
    textColor: "{colors.plate-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.plate}"
    padding: "10dp 16dp"
  plate-work:
    backgroundColor: "{colors.work-orange}"
    textColor: "{colors.plate-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.plate}"
    padding: "10dp 16dp"
  number-plate:
    backgroundColor: "{colors.plate-white}"
    textColor: "{colors.plate-ink}"
    typography: "{typography.figure}"
    rounded: "{rounded.plate}"
    padding: "6dp 10dp 4dp"
  button-primary:
    backgroundColor: "{colors.route-blue}"
    textColor: "{colors.sign-white}"
    typography: "{typography.line}"
    rounded: "{rounded.button}"
    padding: "16dp 40dp"
    height: "96dp"
  button-ground:
    backgroundColor: "{colors.concrete}"
    textColor: "{colors.concrete-ink}"
    typography: "{typography.line}"
    rounded: "{rounded.button}"
    padding: "16dp 40dp"
    height: "96dp"
---

# Design System: 운전선생

## Overview

**Creative North Star: "The Road Sign"**

Every screen is a Korean road sign, the kind built to be read at speed: a flat coloured panel with a white line inset from its edge, big heavy Hangul, numerals in a highway face, small white plates for numbers and footnotes. The score is written on a sign the way a distance is. There is no dashboard chrome, no gauge, no card list; the screen is a ground (concrete by day, asphalt by night) with one to three signs standing on it.

The sign's *kind* is the state. A green guide sign means the score is passing; a yellow caution sign means it has fallen below the pass mark; a white prohibition disc in a red ring means the test is over. Blue route signs carry lists and other destinations. An orange work-zone plate marks what is not built or not judged. The colours are not decoration: each one says exactly one thing, and it says the same thing on every screen.

The density is set by the head unit and the driver. Body text is never below 24sp, touch targets are 96dp, and the driving screen is one sign filling the window, static. Night keeps the sign colours, dimmed, and never floods the cabin with a bright fill.

**Key Characteristics:**
- Flat sign panels with a 4dp line inset 9dp from the edge; no shadows anywhere.
- Sign kind encodes state: guide green, caution yellow, prohibition ring, route blue, work orange.
- Overpass numerals (tabular, heavy) with Gothic A1 Hangul; units fall back to Gothic A1.
- Day on concrete, night on asphalt; the same signs, dimmed.
- One authored motion in the whole app, and never while driving.

## Colors

A road-sign palette: fully saturated sign colours on a neutral concrete or asphalt ground, each colour bound to one meaning. Every colour token has a day value and a `-night` value; the night value is the same sign, darker so it does not glare.

### Primary
- **Guide Green** (`guide-green`): the 안내표지 face. The road-test sign on home, the start screen's list of judged items, the score while it passes, the 합격 verdict. Green always means "on course."
- **Route Blue** (`route-blue`): the second 안내표지 colour. Other destinations on the gantry, the penalty list on the result, the primary buttons (시험 끝내기, 다시 시험, 권한 요청), the 지시표지 start disc.

### Secondary
- **Caution Yellow** (`caution-yellow`): the 주의표지. By day the whole score sign turns yellow below the pass mark; by night it survives only as the inset line and the triangle symbol on a dark panel (`caution-panel-night`, ink `caution-panel-ink-night`). Also the rotary focus ring.
- **Prohibition Red** (`prohibition-red`): the ring of the 규제표지 and the edge of the triangle. Disqualification only: the blank ring that replaces the score, the ring badge in the penalty list, the red-edged 바로 실격 plates. By day the disqualified sign is white with a red line; by night a dark red-black panel (`prohibition-panel-night`) with the red line.

### Tertiary
- **Work-Zone Orange** (`work-orange`): the 작업구역 plate. It means only 준비 중 (not built) or 채점 안 함 (not judged). Never a highlight, never a warning.

### Neutral
- **Concrete** (`concrete`) / **Asphalt** (`asphalt`): the ground every sign stands on, day and night.
- **Concrete Ink** / **Asphalt Ink** and their muted pair: the only text that sits directly on the ground (app name, 시험 시작, the 홈 button and plate edges).
- **Sign White** (`sign-white`, muted at about 78% alpha): all ink on green and blue signs. Muted sign ink is the sign white made translucent over the sign's own colour, never a grey.
- **Plate White** / **Plate Ink**: 보조표지 and number plates; dark ink on a white plate, slightly dimmed at night.
- **Road**, **Road Paint**, **Gantry Steel**: the home gantry's beam and hangers and the asphalt road with its lane paint under the signs.

### Named Rules
**The Sign Kind Rule.** The score's sign kind carries its state: guide green while passing, caution below 70, prohibition when disqualified. Never show a failing score on green or a passing one on yellow.

**The Work-Zone Rule.** Orange means only 준비 중 or 채점 안 함. Any other disclosure, including the simulator disclosure (개발용 빌드 / 시뮬레이터 값), sits on the white 보조표지 plate.

**The Dark Panel Rule.** By night the caution and prohibition states use a dark panel with a coloured inset line and coloured ink, never a bright full-screen yellow or white fill.

## Typography

**Display Font:** Overpass, variable, wght 800 (heavy) or 600 (light), with Gothic A1 as a custom fallback for Hangul units
**Body Font:** Gothic A1 Medium (500), Bold (700), ExtraBold (800)

**Character:** Overpass is Highway Gothic's open descendant, so the figures read like the distances on a sign; Gothic A1 is a plain, sturdy Hangul gothic that holds up at 56sp and at 24sp. Both are bundled (OFL); the system font never renders a glyph.

### Hierarchy
- **Display** (Overpass 800, autosize 40 to 700sp, tracking -0.04em, tabular): the score numeral, which fills its box (700sp cap while driving, 300sp on the result). Its 점 unit sits on the numeral's baseline in Gothic A1.
- **Title** (Gothic A1 800, 56sp): the verdict word (합격 / 불합격 / 실격 / 채점 중), sign titles, the last penalty's name while driving. Driving-screen and home-sign words scale up from Title by 1.3 to 1.6.
- **Headline** (Gothic A1 800, 44sp): app name, gantry destinations, 감점 내역, the 합격선 미만 line, penalty costs.
- **Line** (Gothic A1 800, 32sp): button labels, penalty names in the list, section heads.
- **Body** (Gothic A1 500, 26sp): the teacher's reasons and sign subtitles, in muted sign ink.
- **Label** (Gothic A1 700, 24sp): plate text and fact names. The floor: nothing smaller.
- **Figure** (Overpass 800, 32sp, tabular) and **Figure Light** (Overpass 600, 24sp): number plates, fact values, the elapsed-time column.

Line height is a 1.18 multiplier throughout. Korean wraps by phrase (LINE_BREAK_WORD_STYLE_PHRASE), never mid-word.

### Named Rules
**The Fallback Rule.** Numerals are set in Overpass; the Hangul units beside them (점, 건) fall back to Gothic A1 through a custom fallback family, never to the system font.

**The Font Padding Rule.** Hangul text keeps font padding, because Gothic A1's ㅂ posts rise past the ascent (불 clips to 물 without it). Numerals drop font padding so they sit tight in their box.

**The 24sp Floor Rule.** No text below 24sp (Label). AAOS body text is never smaller.

## Layout

Landscape head unit, one window. The screen is the ground padded 40dp (`l`) on every side; signs are laid out with weighted rows, not a grid. Spacing is tight inside a group (8 / 16dp) and wide between groups (40 / 64dp).

- **Home:** a gantry. The app name and a 지난 시험 plate on top, a 14dp steel beam with 10dp hangers, three signs in weights 1.6 : 1 : 1 with 24dp gaps, and a drawn road beneath whose lanes line up with the signs above.
- **Start:** a guide sign (1.45) listing judged groups as destination rows, equal space above each row; beside it (1) the 홈 button, the start disc, and white plates for notes.
- **Driving:** one sign fills the screen, padded 64dp. Score on the left (weight 2.4 with no penalty, 1.9 with one), a 4dp vertical rule, the last penalty on the right.
- **Result:** the verdict sign over its buttons (0.95) and a route-blue list sign (1.35), its rows read like a sign's destination rows: time, number plate, name and reason, cost.

**The One Sign While Driving Rule.** While driving, the screen is a single sign: the score and the last penalty, nothing else.

## Elevation & Depth

Flat. No shadows, no blur, no elevation. Depth is the road-sign kind: a sign panel stands on the ground by colour contrast, plates sit on signs by being white, and the inset line frames the face. The only overlays are the press ripple (white at 25% by day, 20% by night, clipped to the face) and the 6dp yellow rotary focus ring.

**The Flat Panel Rule.** Signs are painted metal: flat colour and an inset line. Never add a drop shadow or gradient to make one lift.

## Shapes

Gently rounded rectangles in three sizes: signs 22dp, buttons 14dp, plates 10dp. Every sign face carries a 4dp line inset 9dp from its edge, its corner radius reduced by the inset so the line runs parallel to the edge. Plates are flat with an optional 2dp edge (3dp on buttons). Symbols are drawn as paths, bounds-relative, no bitmaps: the caution triangle (red edge, yellow face, black exclamation), the prohibition ring (ring at 20% of the radius), the blue ahead disc, and one road-sign arrow (wide head, straight shaft) shared by the gantry, the road, and the start disc.

## Components

### Signs
Large painted panels; the system's container.
- **Shape:** 22dp corners, 4dp line inset 9dp.
- **Guide / Route:** green or blue face, white line, white ink; 40dp padding (64dp on the driving screen).
- **Caution:** yellow face, black line and ink by day; dark panel, yellow line and ink by night. Carries the triangle symbol beside 합격선 70점 미만.
- **Prohibition:** white face, red line by day; dark red-black panel, red line by night. The score is replaced by a blank prohibition ring; no number stands.
- **Rules inside a sign:** 2dp (4dp vertical on the driving screen) in the sign's muted ink.

### Buttons
Plate-shaped, heavy, and big.
- **Shape:** 14dp corners, at least 96dp tall, 16dp by 40dp padding, Line type.
- **Primary:** route-blue face, sign-white label.
- **Ground:** the ground colour with a 3dp muted-ink edge (홈).
- **Press / Focus:** ripple clipped to the face; rotary focus draws a 6dp caution-yellow ring. Whole signs (the home road-test sign, the start disc) are tappable through the same feedback.

### Plates
Small flat panels that sit on signs or on the ground.
- **보조표지:** white plate, dark ink, Label type, 10dp corners, 10dp by 16dp padding; a muted edge when it sits on the ground, a red edge for the 바로 실격 items.
- **Work-zone:** orange plate, dark ink. 준비 중 and 채점 안 함 only.
- **Number plate:** white plate, Overpass numerals, minimum width about 2.1 times the type size. The item number, shown as signs show route numbers.

### Penalty Row (signature)
The result's memorable moment: rows on a blue sign like a sign's destinations, "0:03 [9] 급조작·급출발 −7", with the teacher's reason under the name in muted Body. A disqualification shows the ring badge and 실격 in place of the plate and cost. A scrollbar thumb in muted sign ink runs in its own 24dp gutter.

### Verdict Motion
The single authored motion: on arrival at the parked result, the verdict sign drops 40dp into place while fading in, 420ms, ease-out cubic-bezier(0.16, 1, 0.3, 1). Parked only, and skipped when system animations are off.

## Do's and Don'ts

### Do:
- **Do** put the score on the sign whose kind matches its state: guide green at 70 or above, caution below 70, prohibition when disqualified.
- **Do** carry every disclosure that is not 준비 중 / 채점 안 함 on the white 보조표지 plate, including the simulator disclosure.
- **Do** use a dark panel with a coloured inset line for caution and prohibition by night.
- **Do** set numerals in Overpass with tabular figures and let Hangul units fall back to Gothic A1.
- **Do** keep font padding on Hangul text and drop it on numerals.
- **Do** keep text at 24sp or larger and touch targets at 96dp.
- **Do** draw symbols as paths in sign colours, from the caution triangle, prohibition ring, ahead disc, and the one arrow.

### Don't:
- **Don't** animate anything on the driving screen. The verdict sign's 420ms drop on the parked result is the only motion.
- **Don't** use orange for anything but 준비 중 and 채점 안 함.
- **Don't** fill the night screen with bright yellow or white for a failing or disqualified state.
- **Don't** show a number on the disqualified sign; the blank ring replaces it.
- **Don't** add shadows, gradients, or gauge-style circular scores; the score is a sign, not a dial.
- **Don't** mute sign ink to a grey; muted ink is the sign's white made translucent over its own colour.
- **Don't** render any glyph in the system font; both faces are bundled.

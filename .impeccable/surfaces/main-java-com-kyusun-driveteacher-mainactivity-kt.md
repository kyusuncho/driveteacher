---
version: 1
slug: "main-java-com-kyusun-driveteacher-mainactivity-kt"
primary_target: "app/src/main/java/com/kyusun/driveteacher/MainActivity.kt"
related_targets: []
---

# 운전선생 앱 화면 (홈 · 시작 · 주행 중 · 결과)

Mode: Operate. 가로형 헤드유닛(Pleos Connect), 주차 중에는 손으로 조작, 주행 중(UX 제한 NO_VIDEO|NO_SETUP)에는 흘끗 보기만.

- 할 일: 주차 중 시험 시작 → 주행 중 점수와 마지막 감점 확인 → 주차 후 시험 끝내기, 감점 이유 읽기.
- 상태: 시험 없음(홈), 시작 전, 주행 중(감점 없음 / 감점 / 70점 미만 / 실격), 주차 중 시험 진행, 결과(합격 / 불합격 / 실격), 채점하지 못한 항목, 권한 없음, 주행 중 홈(시작 불가).
- 제약: 주행 중 동작·애니메이션 없음, 지시 문구 없음. 터치 76dp 이상. 낮·밤 테마. 준비 중 기능은 준비 중으로.
- 기억에 남을 순간: 감점 내역이 표지판의 행선지 줄처럼 "30 진로변경 신호 불이행 −7"로 늘어선 결과 판.
- 미정: 지난 시험 기록 저장(지금은 실행 중 마지막 결과만), 장내기능·모드 2 화면.

## Direction contract

THESIS: 화면은 도로표지판 한 장이다. 달리면서 읽도록 설계된 판에 점수와 감점을 쓴다. 검은 대시보드, 원형 점수 게이지, 카드 목록을 거부한다.
OWN-WORLD: 안내표지 초록·파랑 판과 흰 안쪽 테두리, 흰 보조표지 판, 빨간 테두리 노란 주의 삼각형, 빨간 링 규제 원, 주황 작업구역 판(준비 중). 바탕은 낮 콘크리트, 밤 아스팔트. 숫자 Overpass, 한글 Gothic A1.
STORY: 응시자는 판 하나로 지금 점수와 마지막 감점을 읽고, 차를 세운 뒤 감점마다 이유를 읽고 다시 시험한다.
FIRST VIEWPORT: 주행 중, 초록 판이 화면을 채운다. 왼쪽에 점수 숫자(판 높이의 약 60%), 흰 세로 구분선, 오른쪽에 항목 번호 판과 마지막 감점, 배점. 홈은 갠트리 표지 세 장, 첫 장 "도로주행 시험"이 주 동작.
FORM: 도로표지판, 내 목록 1위(IMPECCABLE'S PICK), seed ca708752.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

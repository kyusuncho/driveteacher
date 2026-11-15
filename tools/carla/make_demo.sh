#!/usr/bin/env bash
# Builds the side-by-side demo (CARLA bird's-eye | Pleos emulator) from a `./run.sh --record DIR` recording.
#   ./run.sh --seconds 90 --record recording && ./make_demo.sh recording ../../assets
# Pieces of Pleos screen recorded after the drive (e.g. the result screen) can be added to DIR/segments.csv; the
# CARLA side stays on its last frame while they play.
# Outputs: carla-pleos-demo.mp4, carla-pleos-demo.gif (first 25 s), carla-pleos-{parked,driving,red-light,end}.png
set -euo pipefail
cd "$(dirname "$0")"
REC=${1:-recording}
OUT=${2:-../../assets}
mkdir -p "$OUT"
FONT=../../app/src/main/assets/fonts/Overpass.ttf  # the app's own numeral face (OFL), so the video ships no system font
VIDEO="$OUT/carla-pleos-demo.mp4"

# The captions are drawn here and laid over the video: Homebrew's ffmpeg has no drawtext (no freetype) since 8.x.
python3 - "$FONT" "$REC/titles.png" <<'PY'
import sys
from PIL import Image, ImageDraw, ImageFont
font = ImageFont.truetype(sys.argv[1], 24)
font.set_variation_by_axes([600])
bar = Image.new("RGB", (2000, 50), (0x18, 0x19, 0x1F))
draw = ImageDraw.Draw(bar)
draw.text((20, 13), "CARLA 0.9.15 in Docker (headless) - birds-eye view", font=font, fill="white")
draw.text((740, 13), "Pleos Connect emulator - fed by tools/carla/bridge.py", font=font, fill="white")
bar.save(sys.argv[2])
PY

# Each CARLA frame lasts until the next one's wall-clock timestamp (the sim doesn't run at exactly real time).
awk -F, 'NR > 1 { f[NR] = $1; w[NR] = $2 }
  END { for (i = 2; i <= NR; i++) {
          printf "file %05d.jpg\nduration %.3f\n", f[i], (i < NR ? w[i + 1] - w[i] : 0.1) }
        printf "file %05d.jpg\n", f[NR] }' "$REC/frames.csv" > "$REC/frames.txt"

# The Pleos screen comes in pieces (the emulator records 180 s at most). Each is held on its last frame until the
# next one starts, so the screen keeps in step with the CARLA frames; the first starts at CARLA frame 0.
inputs=() walls=() durations=()
while IFS=, read -r file wall; do
  inputs+=(-i "$REC/$file")
  walls+=("$wall")
  durations+=("$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$REC/$file")")
done < <(tail -n +2 "$REC/segments.csv")
n=${#walls[@]}
pieces="" labels=""
for ((i = 0; i < n; i++)); do
  hold=0
  if ((i + 1 < n)); then
    hold=$(awk -v a="${walls[i]}" -v d="${durations[i]}" -v b="${walls[i + 1]}" 'BEGIN { h = b - a - d; printf "%.3f", (h > 0 ? h : 0) }')
  fi
  pieces+="[$((i + 2)):v]setpts=PTS-STARTPTS,fps=20,scale=1280:720,setsar=1,tpad=stop_mode=clone:stop_duration=$hold[p$i];"
  labels+="[p$i]"
done
end=$(awk -v a="${walls[n - 1]}" -v d="${durations[n - 1]}" 'BEGIN { printf "%.3f", a + d }')
carla_hold=$(awk -F, -v end="$end" 'NR > 1 { last = $2 } END { h = end - last; printf "%.3f", (h > 0 ? h : 0) }' "$REC/frames.csv")

ffmpeg -y -loglevel error -f concat -safe 0 -i "$REC/frames.txt" -i "$REC/titles.png" "${inputs[@]}" \
  -filter_complex "
  [0:v]fps=20,scale=720:720,setsar=1,tpad=stop_mode=clone:stop_duration=$carla_hold[carla];
  ${pieces}${labels}concat=n=$n:v=1:a=0[pleos];
  [carla][pleos]hstack=inputs=2,pad=2000:770:0:50:color=0x18191f[stack];
  [stack][1:v]overlay=0:0[v]" \
  -map "[v]" -an -c:v libx264 -crf 22 -pix_fmt yuv420p -shortest "$VIDEO"

ffmpeg -y -loglevel error -t 25 -i "$VIDEO" -vf \
  "fps=8,scale=1000:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer" \
  "$OUT/carla-pleos-demo.gif"

# Stills at moments picked from frames.csv (seconds since frame 0).
moments=$(awk -F, 'NR == 2 { t0 = $2 } NR > 1 {
    t = $2 - t0
    if (!drive && $3 == "drive" && $4 > 20) drive = t + 1
    # driver.py stops short of the light trigger area (light "-"), but waits in N
    if (!red && $3 == "drive" && ($6 == "Red" || $5 == "Neutral") && $4 < 1) red = t + 5 }
  END { printf "parked 2\ndriving %.1f\n", drive; if (red) printf "red-light %.1f\n", red }' \
  "$REC/frames.csv")
moments+=$'\n'"end $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$VIDEO" | awk '{ printf "%.1f", $1 - 1 }')"
while read -r name at; do
  ffmpeg -y -loglevel error -ss "$at" -i "$VIDEO" -frames:v 1 "$OUT/carla-pleos-$name.png"
  echo "still $name at ${at}s"
done <<< "$moments"
ls -lh "$OUT"/carla-pleos-*

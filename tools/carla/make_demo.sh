#!/usr/bin/env bash
# Builds the side-by-side demo (CARLA bird's-eye | Pleos emulator) from a `./run.sh --record DIR` recording.
#   ./run.sh --seconds 90 --record recording && ./make_demo.sh recording ../../assets
# Outputs: carla-pleos-demo.mp4, carla-pleos-demo.gif (first 25 s), carla-pleos-{parked,driving,red-light,end}.png
set -euo pipefail
cd "$(dirname "$0")"
REC=${1:-recording}
OUT=${2:-../../assets}
mkdir -p "$OUT"
FONT=/System/Library/Fonts/Helvetica.ttc
VIDEO="$OUT/carla-pleos-demo.mp4"

# Each CARLA frame lasts until the next one's wall-clock timestamp (the sim doesn't run at exactly real time).
awk -F, 'NR > 1 { f[NR] = $1; w[NR] = $2 }
  END { for (i = 2; i <= NR; i++) {
          printf "file %05d.jpg\nduration %.3f\n", f[i], (i < NR ? w[i + 1] - w[i] : 0.1) }
        printf "file %05d.jpg\n", f[NR] }' "$REC/frames.csv" > "$REC/frames.txt"

# Both streams start together: the bridge starts the emulator recording at CARLA frame 0.
ffmpeg -y -loglevel error -f concat -safe 0 -i "$REC/frames.txt" -i "$REC/pleos.webm" -filter_complex "
  [0:v]fps=20,scale=720:720,setsar=1[carla];
  [1:v]fps=20,scale=1280:720,setsar=1[pleos];
  [carla][pleos]hstack=inputs=2,pad=2000:770:0:50:color=0x18191f,
  drawtext=fontfile=$FONT:text='CARLA 0.9.15 in Docker (headless) - birds-eye view':x=20:y=13:fontsize=24:fontcolor=white,
  drawtext=fontfile=$FONT:text='Pleos Connect emulator - fed by tools/carla/bridge.py':x=740:y=13:fontsize=24:fontcolor=white[v]" \
  -map "[v]" -an -c:v libx264 -crf 22 -pix_fmt yuv420p -shortest "$VIDEO"

ffmpeg -y -loglevel error -t 25 -i "$VIDEO" -vf \
  "fps=8,scale=1000:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer" \
  "$OUT/carla-pleos-demo.gif"

# Stills at moments picked from frames.csv (seconds since frame 0).
moments=$(awk -F, 'NR == 2 { t0 = $2 } NR > 1 {
    t = $2 - t0; last = t
    if (!drive && $3 == "drive" && $4 > 20) drive = t + 1
    # driver.py stops short of the light trigger area (light "-"), but waits in N
    if (!red && $3 == "drive" && ($6 == "Red" || $5 == "Neutral") && $4 < 1) red = t + 5 }
  END { printf "parked 2\ndriving %.1f\n", drive; if (red) printf "red-light %.1f\n", red; printf "end %.1f\n", last - 1 }' \
  "$REC/frames.csv")
while read -r name at; do
  ffmpeg -y -loglevel error -ss "$at" -i "$VIDEO" -frames:v 1 "$OUT/carla-pleos-$name.png"
  echo "still $name at ${at}s"
done <<< "$moments"
ls -lh "$OUT"/carla-pleos-*

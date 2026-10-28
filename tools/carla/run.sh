#!/usr/bin/env bash
# Start the headless CARLA server (if needed) and run the CARLA -> Pleos emulator bridge.
# Requires: Docker Desktop running, Pleos emulator booted (emulator-5554). Args go to bridge.py.
set -euo pipefail
cd "$(dirname "$0")"

adb forward tcp:8088 tcp:8088 >/dev/null
EMULATOR_TOKEN="$(cat ~/.emulator_console_auth_token)"
export EMULATOR_TOKEN

docker compose up -d carla >/dev/null
until docker compose run --rm -T client python -c \
  "import carla; c = carla.Client('carla', 2000); c.set_timeout(5); c.get_server_version()" >/dev/null 2>&1; do
  echo "waiting for CARLA server..."
  sleep 5
done

exec docker compose run --rm -T client python bridge.py "$@"

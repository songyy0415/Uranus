#!/usr/bin/env bash
# Production smoke check: launch KnotServer exactly like a real production install
# (mods in run/mods, no dev classpath, fabric.development=false), wait for the
# server to reach "Done (Xs)!" or crash, then kill java. Writes a clean UTF-8 log.
set -u

cd /d/aiminecraftdev/Uranus/fabric/run || exit 2

LOG=/d/aiminecraftdev/Uranus/fabric/run-prod-server.log
CP=$(tr -d '\r' < /d/aiminecraftdev/Uranus/fabric/build/prod-classpath.txt | paste -sd';')
MCJAR='D:/aiminecraftdev/Uranus/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-8943ec45d3/26.2/minecraft-merged-8943ec45d3-26.2.jar'

: > "$LOG"
"/c/Program Files/Zulu/zulu-25/bin/java.exe" -cp "$CP" "-Dfabric.gameJarPath=$MCJAR" \
  -Dfabric.development=false net.fabricmc.loader.launch.knot.KnotServer --nogui > "$LOG" 2>&1 &
JAVAPID=$!
echo "java pid=$JAVAPID"

for i in $(seq 1 150); do
  if grep -q "Done ([0-9]" "$LOG"; then
    echo "== RESULT: SERVER_DONE =="
    cmd //c "taskkill /F /PID $JAVAPID" >/dev/null 2>&1
    break
  fi
  if grep -qiE "mixinapplyerror|failed to apply mixin|mixin apply for mod|exception in thread|fatal error|is not a valid endpoint" "$LOG"; then
    echo "== RESULT: SERVER_ERROR =="
    cmd //c "taskkill /F /PID $JAVAPID" >/dev/null 2>&1
    break
  fi
  if ! kill -0 "$JAVAPID" 2>/dev/null; then
    echo "== RESULT: SERVER_EXITED_EARLY =="
    break
  fi
  sleep 2
done

wait "$JAVAPID" 2>/dev/null
echo "== log size: $(wc -c < "$LOG") bytes =="

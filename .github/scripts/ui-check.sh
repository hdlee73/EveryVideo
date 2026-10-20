#!/usr/bin/env bash
# 에뮬레이터에서 시험 동영상을 열어 세로(작업 화면)·가로·큰 화면 가로(패널·미리보기·패널 접기)를 찍고, 앱이 죽지 않았는지 본다.
set -u
PKG=com.everyvideo.player
DIR=/sdcard/Download
OUT=ui-shots
mkdir -p "$OUT"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=d=40:s=1280x720:r=30 -f lavfi -i sine=d=40 \
  -c:v libx264 -pix_fmt yuv420p -g 30 -c:a aac -shortest test.mp4
adb install -r dist/*.apk
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
adb push test.mp4 "$DIR/test.mp4"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c
adb shell am start -W -a android.intent.action.VIEW -t video/mp4 -d "file://$DIR/test.mp4" -n "$PKG/.ui.PlayerActivity"
sleep 15
adb exec-out screencap -p > "$OUT/portrait.png"
adb shell settings put system user_rotation 1
sleep 8
adb exec-out screencap -p > "$OUT/landscape.png"
adb shell settings put system user_rotation 0
sleep 5
adb exec-out screencap -p > "$OUT/portrait-again.png"
# 큰 화면(태블릿·DeX) 가로: 오른쪽 도구 패널 → 시간 막대를 끄는 중 장면 미리보기 → P 로 패널 접기
adb shell wm size 1600x2560
adb shell wm density 320
sleep 10
adb shell settings put system user_rotation 1
sleep 8
adb shell input keyevent KEYCODE_SPACE
sleep 2
adb exec-out screencap -p > "$OUT/tablet-side.png"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$(adb shell cat /sdcard/ui.xml | grep -o 'resource-id="com.everyvideo.player:id/timeBar"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
echo "timeBar bounds: $b"
adb shell cat /sdcard/ui.xml | grep -o '<node[^>]*timeBar[^>]*>' | head -2
# 못 찾으면 이 에뮬레이터(2560x1600 가로)의 패널 막대 자리를 쓴다
[ -n "$b" ] || b="1830 100 2490 160"
if [ -n "$b" ]; then
  set -- $b
  y=$(( ($2 + $4) / 2 ))
  adb shell input swipe $(( $1 + 40 )) $y $(( $1 + ($3 - $1) * 6 / 10 )) $y 6000 &
  sleep 4
  adb exec-out screencap -p > "$OUT/tablet-drag.png"
  wait
fi
adb shell input keyevent KEYCODE_P
sleep 3
adb exec-out screencap -p > "$OUT/tablet-hidden.png"
adb logcat -d > "$OUT/logcat.txt"
crashed=0
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::error::app crashed"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60; crashed=1
fi
if [ -z "$(adb shell pidof $PKG)" ]; then echo "::error::app is not running"; crashed=1; fi
# 화면 확인용: 작은 JPEG 를 로그에 남긴다 (SHOT-<이름>: 줄을 이어 붙여 base64 디코딩)
for n in portrait landscape tablet-side tablet-drag tablet-hidden; do
  [ -f "$OUT/$n.png" ] || continue
  ffmpeg -hide_banner -loglevel error -y -i "$OUT/$n.png" -vf "scale='if(gt(iw,ih),900,420)':-2" -q:v 6 "$OUT/$n.jpg"
  base64 -w 4000 "$OUT/$n.jpg" | sed "s/^/SHOT-$n:/"
done
exit $crashed

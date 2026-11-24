set -e
[ -d "$PREFIX/lib/jvm/java-21-openjdk" ] && export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
cd "$(dirname "$0")/.."
OK=0
for t in 1 2 3; do
  if "$HOME/.gradle/gradle-8.10.2/bin/gradle" --no-daemon --console=plain assembleDebug; then OK=1; break; fi
  echo "Build fail (try $t/3). JitPack par library ban rahi ho sakti hai, 2 min ruk kar dobara..."
  sleep 120
done
[ "$OK" = 1 ] || { echo "BUILD FAIL: 'What went wrong' ki pehli lines bhejo"; exit 1; }
OUT=/storage/emulated/0/activate/music
mkdir -p "$OUT"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT/KhanMusic.apk"
echo "DONE: $OUT/KhanMusic.apk"

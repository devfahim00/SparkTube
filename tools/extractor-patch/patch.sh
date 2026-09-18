#!/usr/bin/env bash
# Regenerates app/libs/newpipeextractor-0.26.5-android-compat.jar from the upstream
# NewPipeExtractor v0.26.5 release (JitPack build).
#
# WHY: v0.26.5's org.schabi.newpipe.extractor.utils.Utils calls
#   URLDecoder.decode(String, Charset) and URLEncoder.encode(String, Charset)
# Those are Java 10 APIs, available on Android only from API level 33, so every
# video open crashed with NoSuchMethodError on Android 10 and below (Firebase
# Crashlytics report). Core library desugaring does NOT cover java.net, and
# v0.26.5 is the latest extractor release, so there was no upstream fix.
#
# WHAT: AuditPatch (ASM) rewrites those two invokestatic call sites to call
# AndroidUrlCompat.decodeUtf8/encodeUtf8, which use the (String, String)
# overloads that exist on every Android version. The helper class is injected
# into the patched jar.
#
# USAGE (a JRE is enough — the script uses the Eclipse batch compiler):
#   tools/extractor-patch/patch.sh
#
# TO UPGRADE THE EXTRACTOR LATER: download the new JitPack jar, update
# EXTRACTOR_VERSION below (plus the app/libs/ jar name and build.gradle.kts
# references and transitive dependency list), re-run this script, review the
# audit output in out/audit.txt for other java.* APIs newer than API 24 that
# are not desugared (URLDecoder-style whack-a-mole), then commit the new jar.

set -euo pipefail
cd "$(dirname "$0")"
mkdir -p deps out

EXTRACTOR_VERSION="v0.26.5"
OUT_JAR="newpipeextractor-0.26.5-android-compat.jar"

dl() { curl -sL -f --max-time 90 -o "deps/$2" "$1" && echo "OK  $2" || { echo "FAIL $2"; exit 1; }; }

# Eclipse batch compiler: lets us compile with a JRE-only environment (no javac).
[ -f deps/ecj.jar ] || dl https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.36.0/ecj-3.36.0.jar ecj.jar
[ -f deps/asm-9.7.jar ] || dl https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar asm-9.7.jar
[ -f "deps/NewPipeExtractor-$EXTRACTOR_VERSION.jar" ] || \
    dl "https://jitpack.io/com/github/TeamNewPipe/NewPipeExtractor/$EXTRACTOR_VERSION/NewPipeExtractor-$EXTRACTOR_VERSION.jar" "NewPipeExtractor-$EXTRACTOR_VERSION.jar"

java -jar deps/ecj.jar -8 -nowarn -cp deps/asm-9.7.jar -d out AuditPatch.java AndroidUrlCompat.java

# Audit (all java.* method refs) + patch (URLDecoder/URLEncoder Charset overloads)
java -cp out:deps/asm-9.7.jar AuditPatch --audit "deps/NewPipeExtractor-$EXTRACTOR_VERSION.jar" > out/audit.txt || true
java -cp out:deps/asm-9.7.jar AuditPatch --patch \
    "deps/NewPipeExtractor-$EXTRACTOR_VERSION.jar" \
    "out/$OUT_JAR" \
    out/org/schabi/newpipe/extractor/utils/AndroidUrlCompat.class

# Verify: no Charset overloads left; helper inside the jar
java -cp out:deps/asm-9.7.jar AuditPatch --audit "out/$OUT_JAR" > out/audit-patched.txt || true
if grep -q "Ljava/nio/charset/Charset;)Ljava/lang/String;" out/audit-patched.txt; then
    echo "ERROR: Charset-overload invokes still present — patch did not apply!"
    exit 1
fi
unzip -l "out/$OUT_JAR" | grep AndroidUrlCompat

echo ""
echo "Patched jar ready: out/$OUT_JAR"
echo "Copy it over app/libs/$OUT_JAR and commit."

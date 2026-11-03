#!/usr/bin/env bash
# Exercises mvnrun.java end to end. Usage: test.sh <path-to-mvnrun.java> <work-dir>
set -u
SCRIPT="$1"
WORK="${2:?work directory required}"
rm -rf "${WORK:?}"
mkdir -p "$WORK/run" "$WORK/mirror"
export JBANG_CACHE_DIR="$WORK/cache"
unset JAVA_TOOL_OPTIONS MAVEN_OPTS
REPO="-Dmaven.repo.local=$WORK/m2"
PASS=0
FAIL=0
LOG="$WORK/last.log"

check() { # name, condition exit status
	if [ "$2" -eq 0 ]; then
		echo "PASS  $1"
		PASS=$((PASS + 1))
	else
		echo "FAIL  $1"
		sed 's/^/      | /' "$LOG" | tail -15
		FAIL=$((FAIL + 1))
	fi
}
run() {
	(cd "$WORK/run" && jbang "$SCRIPT" "$@") >"$LOG" 2>&1
	RC=$?
}
home() { echo "$WORK/cache/mvnrun/apache-maven-$1"; }

run
[ "$RC" -eq 2 ] && grep -q "no goal specified" "$LOG"
check "no arguments gives usage and exit 2" $?

run --maven-version ../../evil help:help
[ "$RC" -eq 2 ] && grep -q "invalid Maven version" "$LOG" && [ ! -e "$WORK/cache/mvnrun" ]
check "path traversal in version is rejected" $?

run --maven-version 0.0.0 help:help
[ "$RC" -eq 1 ] && grep -q "HTTP 404" "$LOG"
check "unknown Maven version gives a clean error" $?

# Mirror serving a wrong checksum: nothing may be installed.
V=3.9.16
M="$WORK/mirror/org/apache/maven/apache-maven/$V"
mkdir -p "$M"
curl -fsSL -o "$M/apache-maven-$V-bin.zip" "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$V/apache-maven-$V-bin.zip"
printf '%0128d\n' 0 >"$M/apache-maven-$V-bin.zip.sha512"
(cd "$WORK/mirror" && exec python3 -m http.server 18765 --bind 127.0.0.1) >/dev/null 2>&1 &
SERVER=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do curl -fs -o /dev/null http://127.0.0.1:18765/ && break; sleep 0.3; done
MVNRUN_REPO_URL=http://127.0.0.1:18765 run help:help
[ "$RC" -eq 1 ] && grep -q "SHA-512 mismatch" "$LOG" && [ ! -e "$(home $V)" ] && [ -z "$(ls -A "$WORK/cache/mvnrun")" ]
check "checksum mismatch installs nothing and leaves no temp files" $?

# Same mirror with the right checksum proves the repo override really is used.
curl -fsSL -o "$M/apache-maven-$V-bin.zip.sha512" "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$V/apache-maven-$V-bin.zip.sha512"
MVNRUN_REPO_URL=http://127.0.0.1:18765/ run --version
[ "$RC" -eq 0 ] && grep -q "from http://127.0.0.1:18765/org" "$LOG" && grep -q "Apache Maven $V" "$LOG"
check "MVNRUN_REPO_URL with verified checksum installs from mirror" $?
kill "$SERVER" 2>/dev/null
wait "$SERVER" 2>/dev/null
rm -rf "$WORK/cache/mvnrun"

# Concurrent first runs share the one published install. Runs the compiled class directly so
# that this measures mvnrun's install race rather than the concurrent behaviour of the launcher.
javac -d "$WORK/classes" "$SCRIPT"
RACE_FAIL=0
: >"$LOG"
for round in 1 2 3 4 5; do
	rm -rf "$WORK/cache/mvnrun"
	PIDS=()
	for n in 1 2 3 4; do
		(cd "$WORK/run" && java -cp "$WORK/classes" mvnrun --version) >"$WORK/c$n.log" 2>&1 &
		PIDS+=($!)
	done
	for p in "${PIDS[@]}"; do wait "$p" || RACE_FAIL=1; done
	cat "$WORK"/c?.log >>"$LOG"
	[ -n "$(find "$WORK/cache/mvnrun" -maxdepth 1 -name '.install-*')" ] && RACE_FAIL=1
done
[ "$RACE_FAIL" -eq 0 ]
check "5 rounds of 4 concurrent first runs all succeed with no leftovers" $?

run --version
[ "$RC" -eq 0 ] && ! grep -q "Downloading" "$LOG"
check "second run reuses the cache" $?

run help:evaluate -Dexpression=java.version -q -DforceStdout "$REPO"
[ "$RC" -eq 0 ] && grep -Eq '^[0-9]+\.[0-9]' "$LOG"
check "plugin prefix goal without a POM (help:evaluate): $(tail -1 "$LOG" | cut -c1-30)" $?

run org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.slf4j:slf4j-api:2.0.13 -Dtransitive=false -B "$REPO"
[ "$RC" -eq 0 ] && [ -f "$WORK/m2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar" ]
check "fully qualified goal does real work (dependency:get)" $?

run org.apache.maven.plugins:maven-archetype-plugin:3.3.1:generate -B -DarchetypeGroupId=org.apache.maven.archetypes -DarchetypeArtifactId=maven-archetype-quickstart -DarchetypeVersion=1.5 -DgroupId=org.acme -DartifactId=demo -Dversion=1.0 "$REPO"
[ "$RC" -eq 0 ] && [ -f "$WORK/run/demo/pom.xml" ]
check "goal writes into the caller's directory (archetype:generate)" $?

run help:evaluate "-Dexpression=project.name" "-Dfoo=a b  c" -q -DforceStdout "$REPO"
[ "$RC" -eq 0 ]
check "argument containing spaces passes through" $?

run org.acme.nope:nope-maven-plugin:1.0:goal -B "$REPO"
[ "$RC" -eq 1 ] && grep -q "nope-maven-plugin" "$LOG"
check "failing goal propagates Maven's exit code" $?

run compile -B "$REPO"
[ "$RC" -ne 0 ] && grep -q "there is no POM in this directory" "$LOG"
check "goal needing a project reports Maven's no-POM error" $?

(cd "$WORK/run" && env -u JAVA_HOME jbang "$SCRIPT" --version) >"$LOG" 2>&1
RC=$?
[ "$RC" -eq 0 ] && grep -q "Java version" "$LOG"
check "works with JAVA_HOME unset" $?

(cd "$WORK/run" && env JAVA_HOME="$WORK/not-a-jdk" PATH="$PATH" jbang "$SCRIPT" --version) >"$LOG" 2>&1
RC=$?
grep -q "JAVA_HOME is set but does not seem to point to a valid Java JDK" "$LOG"
check "caller's JAVA_HOME reaches Maven, not overridden (Maven warns on bad value)" $?

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]

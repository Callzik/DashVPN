#!/bin/bash
# Runs the Android parser, speed check and subscription info tests on a plain JVM (no SDK needed).
set -euo pipefail
export JAVA_TOOL_OPTIONS=
cd "$(dirname "$0")/.."
B=build/test
rm -rf $B && mkdir -p $B
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -d $B \
  src/app/dash/{SubParser,Server,Json,XrayJson,Speed,Fetch,SubInfo}.java test/app/dash/{SubParserTest,SpeedTest,FetchTest,SubInfoTest}.java
java -cp $B app.dash.SubParserTest testdata/sub-cases.json
java -cp $B app.dash.SpeedTest
java -cp $B app.dash.FetchTest
java -cp $B app.dash.SubInfoTest

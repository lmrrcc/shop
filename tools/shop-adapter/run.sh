#!/bin/sh
set -e
root=$(cd "$(dirname "$0")/../.." && pwd)
"$root/mvnw" -q -B -ntp -f "$root/tools/shop-adapter/pom.xml" -DskipTests package 1>&2
exec java -jar "$root/tools/shop-adapter/target/shop-adapter.jar" "$@"

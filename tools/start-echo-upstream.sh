#!/usr/bin/env bash
# 起本地回显上游（8091），用来验证网关是否把请求按路由转发了出去。
# 用法：bash tools/start-echo-upstream.sh
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_BIN="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home}/bin/java"
exec "$JAVA_BIN" "$DIR/echo-upstream/EchoUpstream.java"

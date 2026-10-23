#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec mvn test -Dtest='com.gic.explanation.*Test' "$@"

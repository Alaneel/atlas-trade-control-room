#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ATLAS_MONGO_URI:?Set ATLAS_MONGO_URI to a disposable loopback MongoDB replica set URI}"
if [[ "${1:-}" == "--benchmark" ]]; then
  shift
  exec mvn test -Dtest=ReliableExecutionMongoTest,LedgerSchemaMongoTest,MongoCommitFailureTest,ExplanationMongoTest,ReliableExecutionBenchmarkTest \
    -Datlas.benchmark=true "$@"
fi
exec mvn test -Dtest=ReliableExecutionMongoTest,LedgerSchemaMongoTest,MongoCommitFailureTest,ExplanationMongoTest "$@"

# Executed validation — 20 October 2026

This record describes a local macOS arm64 / Java 17.0.14 verification. MongoDB ran in one Docker Desktop Linux/arm64 container, capped at two CPUs and 512 MiB, on the same host as the Java client. This is not an independent-machine or high-availability result.

## Acceptance

The final `mvn verify` run passed **57 tests, zero failures/errors/skips**, including:

- 19 money/request/worker unit cases;
- 9 HTTP contract cases and 4 existing exception-handler cases;
- one full application context against the replica set;
- 15 real Mongo transaction cases;
- 6 malformed-ledger startup cases;
- 2 real Mongo command-failure cases;
- one optional benchmark case with two measured workloads.

[Per-suite and per-case results](examples/reliable-execution/test-summary.json). Actual command and denominator are included. Database tests without their prerequisites are skipped; a default unit run must not be called this full acceptance run.

The Mongo failure cases injected an `UnknownTransactionCommitResult` on commit and a `TransientTransactionError` on insert. The unknown-commit case executed its business callback once and retained one committed document. The aborted-transaction case executed its callback twice and retained one committed document. These bounded command errors do not cover every network/commit failure.

The reliability-package line count was 285 covered / 336 total (84.8%). JaCoCo enforces a 50% line floor on the actual slash-form `com/gic/reliability` package. The package selector was corrected and the gate separately rerun after runtime experiments; no runtime class changed for that correction. Coverage is supplementary, not a substitute for the conservation and failure tests.

## Real HTTP demonstration

[Raw HTTP results](examples/reliable-execution/atlas-http-demo.json) retain responses, latencies and checks against the packaged JVM:

- 100 distinct requests of 10.00 competed for 100.00 capacity with 12 client workers.
- Exactly 10 executed and 90 were rejected; all 100 initial HTTP responses were 200 with explicit business states.
- 100 repeated submissions retrieved the same completed trade without another debit.
- Changed-payload reuse returned 409; a missing key and fractional-cent amount returned 400.
- Final available/reserved/used was 0.00 / 0.00 / 100.00, with ordered audit events.

## Actual process crash and restart

[Raw process experiment](examples/reliable-execution/atlas-process-recovery.json) records the executed JAR/script hashes, child process return codes and raw database counts. With recovery polling disabled, the simulated venue persisted a success receipt but lost its acknowledgement. The API returned 202 UNKNOWN with available/reserved/used 90.00 / 10.00 / 0.00.

The test sent actual SIGKILL to its owned JVM (return code -9), restarted a new JVM against the same generated database with seeding enabled, and observed the same UNKNOWN state and reservation. Query-only reconciliation settled it, and 20 same-key replays left exactly one trade, one venue receipt and four ordered status events. Final balances were 90.00 / 0.00 / 10.00. Instrument/limit counts stayed 4066 / 440. The restarted child was stopped in `finally`.

This is one real process boundary after an uncertain response. It does not claim arbitrary-instruction crash coverage, real-exchange correctness or multi-node failover. The experiment ran on the dirty working tree based on the recorded base revision; the artifact says so rather than assigning an unexecuted commit to the run. [Final source manifest](examples/reliable-execution/source-manifest.json).

## Durable service benchmark

[Raw samples and aggregate](examples/reliable-execution/atlas-benchmark.json). Each workload ran 500 requests with 8 workers, after 50 warmup requests. The measured interval includes exact ledger reservation, leased submission, persisted simulated receipt, settlement and durable-state inspection. It excludes HTTP, a real venue and admission queue wait.

| Workload | Executions/s | p50 | p95 | p99 | Transaction retries | Request errors / pending |
|---|---:|---:|---:|---:|---:|---:|
| One hot account | 82.8 | 40.9 ms | 407.3 ms | 749.3 ms | 1666 | 0 / 0 |
| 64 accounts | 267.5 | 29.5 ms | 36.7 ms | 40.7 ms | 31 | 0 / 0 |

Both conservation and terminal-completion checks passed. This single short, closed-loop local run illustrates account contention; 500 samples cannot establish stable population p99 or production capacity. Throughput is not an optimization claim against the old simulated endpoint.

An [initial failed diagnostic run](examples/reliable-execution/initial-hotspot-benchmark.json) is retained: the old synchronized retry schedule produced 7 request errors in the hot workload and left 3 pending reservations. It led to bounded full-jitter retries and explicit attempt/retry/exhaustion counters. Its earlier harness incorrectly included terminal completion inside its `ledger_invariant_passed` flag; the final harness reports conservation and completion separately. The earlier uncommitted source was not fingerprinted, so these artifacts are not a controlled before/after performance comparison.

## Additional checks and limits

`docker compose config -q`, Python compilation, shell syntax and `git diff --check` passed. The Java API was exercised as a packaged host JVM; Docker image build and browser interaction with the revised Streamlit form were not executed in this validation. Compose's replica initialization and container recipes remain reviewable configuration, not claimed container acceptance.

The current implementation uses the existing Spring Boot 2.6 dependency line and unauthenticated local APIs. It does not establish security hardening, a production approval system, event-sourced ledger reconstruction or AI explanation quality. See [design, setup and remaining work](RELIABLE_EXECUTION.md).

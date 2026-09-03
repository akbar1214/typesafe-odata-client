#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
ASYNC_PROFILER="$PROJECT_DIR/async-profiler"
BENCH_CLASS="io.github.akbarhusain.odata.core.bench.CodegenBenchmark"
MODULE="odata-codegen-core"
OUTPUT_DIR="$PROJECT_DIR/target/codegen-bench-results"
CP_FILE="$PROJECT_DIR/target/odata-codegen-bench-cp.txt"
BENCH_DIR="$PROJECT_DIR/$MODULE/src/test/java/io/github/akbarhusain/odata/core/bench"
mkdir -p "$OUTPUT_DIR" "$PROJECT_DIR/target"

event=${1:-alloc}
duration=${2:-45}
iterations=${3:-6}

echo "=== OData Codegen Benchmark (15,000 entities, 5 schemas) ==="
echo "Event: $event, Duration: ${duration}s, Iterations: $iterations"

# Build classpath (test scope: junit/slf4j-simple/runtime)
echo "[1/4] Building classpath..."
cd "$PROJECT_DIR"
mvn -q -pl "$MODULE" dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -DincludeScope=test
CLASSES="$PROJECT_DIR/$MODULE/target/classes"
TEST_CLASSES="$PROJECT_DIR/$MODULE/target/test-classes"
CP="$(cat "$CP_FILE"):$CLASSES:$TEST_CLASSES"

# Compile benchmark
echo "[2/4] Compiling benchmark..."
javac -cp "$CP" -d "$TEST_CLASSES" "$BENCH_DIR/CodegenBenchmark.java"

# Run benchmark in background + profile
echo "[3/4] Starting benchmark (pid will be captured)..."
BENCH_LOG="$OUTPUT_DIR/benchmark.log"
java -Xms1g -Xmx4g -cp "$CP" "$BENCH_CLASS" "$iterations" > "$BENCH_LOG" 2>&1 &
BENCH_PID=$!

echo "Benchmark PID: $BENCH_PID"

# Wait for warmup
echo "Waiting for warmup to complete..."
while true; do
    if grep -q "WARMUP_COMPLETE" "$BENCH_LOG" 2>/dev/null; then
        echo "Warmup complete. Starting profiler..."
        break
    fi
    if ! kill -0 "$BENCH_PID" 2>/dev/null; then
        echo "ERROR: Benchmark process died before warmup!"
        cat "$BENCH_LOG"
        exit 1
    fi
    sleep 0.5
done

OUTPUT_FILE="$OUTPUT_DIR/$event-flame.svg"

# Start async-profiler
echo "[4/4] Profiling ($event, ${duration}s)..."
"$ASYNC_PROFILER/bin/asprof" start -e "$event" -o flamegraph -f "$OUTPUT_FILE" "$BENCH_PID"
sleep "$duration"
"$ASYNC_PROFILER/bin/asprof" stop -f "$OUTPUT_FILE" "$BENCH_PID" 2>/dev/null || true

# Wait for benchmark to complete
wait "$BENCH_PID" 2>/dev/null || true

echo ""
echo "=== Results ==="
cat "$BENCH_LOG" | grep "RESULT"
echo "Flamegraph: $OUTPUT_FILE"
echo "Full log: $BENCH_LOG"

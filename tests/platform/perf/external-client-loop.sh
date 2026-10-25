#!/usr/bin/env bash
# External (second-machine) load-generator loop for the two-machine 72-hour soak.
#
# Runs on the GENERATOR host (e.g. 192.0.2.10) while tests/platform/perf/soak.py
# samples the candidate on the SERVER host (e.g. 192.0.2.20). It cycles the soak
# phases mixed90_10 (1:9) -> get_only (0:1) -> set_only (1:0), each phase a bounded
# run of the reviewed stdlib generator (`generator.py`), for `--planned-seconds`.
#
# It writes the per-phase generator reports and a `client-report.json` carrying the
# exact argv, UTC start/end, per-phase exit codes/results, gaps and failures. The
# server host's `externalTrafficGate` plus these artifacts together evidence that a
# real cross-machine load ran. It is safe to run detached (no TTY), needs no root,
# and never overwrites existing files: use `--resume <dir>` to add a new labelled
# attempt into an existing evidence directory.
set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVER_HOST=""
PORT=11219
PLANNED_SECONDS=""
PHASE_SECONDS=""
OUT=""
RESUME=""
GENERATOR="$SCRIPT_DIR/generator.py"
PYTHON="python3"
WORKERS=8
CONNECTIONS_PER_WORKER=4
PIPELINE=1
KEYS=4096
VALUE_MIX="256:1.0"

PHASE_NAMES=("mixed90_10" "get_only" "set_only")
PHASE_RATIOS=("1:9" "0:1" "1:0")

usage() {
  cat <<'USAGE'
Usage: external-client-loop.sh --server-host HOST --planned-seconds N --phase-seconds N
                               (--out DIR | --resume DIR) [options]

Required:
  --server-host HOST      server under test (the soak.py host), e.g. 192.0.2.20
  --planned-seconds N     total planned wall seconds for the client loop
  --phase-seconds N       seconds per phase (mixed90_10, get_only, set_only)
  --out DIR               fresh evidence directory (must not exist or be empty)
  --resume DIR            existing evidence directory; writes new tagged files

Options:
  --port N                server port (default 11219)
  --generator PATH        generator.py path (default: next to this script)
  --python PATH           python interpreter (default: python3)
  --workers N             generator workers (default 8)
  --connections-per-worker N  connections per worker (default 4)
  --pipeline N            pipeline depth (default 1)
  --keys N                dataset key count (default 4096)
  --value-mix SPEC        value size mix (default 256:1.0)
  -h, --help              this help
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --server-host) SERVER_HOST="${2:-}"; shift 2 ;;
    --port) PORT="${2:-}"; shift 2 ;;
    --planned-seconds) PLANNED_SECONDS="${2:-}"; shift 2 ;;
    --phase-seconds) PHASE_SECONDS="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --resume) RESUME="${2:-}"; shift 2 ;;
    --generator) GENERATOR="${2:-}"; shift 2 ;;
    --python) PYTHON="${2:-}"; shift 2 ;;
    --workers) WORKERS="${2:-}"; shift 2 ;;
    --connections-per-worker) CONNECTIONS_PER_WORKER="${2:-}"; shift 2 ;;
    --pipeline) PIPELINE="${2:-}"; shift 2 ;;
    --keys) KEYS="${2:-}"; shift 2 ;;
    --value-mix) VALUE_MIX="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { echo "external-client-loop: $*" >&2; exit 2; }

[ -n "$SERVER_HOST" ] || die "--server-host is required"
[ -n "$PLANNED_SECONDS" ] || die "--planned-seconds is required"
[ -n "$PHASE_SECONDS" ] || die "--phase-seconds is required"
[ -z "$OUT" ] || [ -z "$RESUME" ] || die "--out and --resume are mutually exclusive"
[ -n "$OUT" ] || [ -n "$RESUME" ] || die "--out or --resume is required"
case "$PLANNED_SECONDS" in ''|*[!0-9]*) die "--planned-seconds must be a positive integer" ;; esac
case "$PHASE_SECONDS" in ''|*[!0-9]*) die "--phase-seconds must be a positive integer" ;; esac
[ "$PLANNED_SECONDS" -gt 0 ] || die "--planned-seconds must be > 0"
[ "$PHASE_SECONDS" -gt 0 ] || die "--phase-seconds must be > 0"
[ -f "$GENERATOR" ] || die "generator not found: $GENERATOR"

if [ -n "$RESUME" ]; then
  [ -d "$RESUME" ] || die "--resume directory does not exist: $RESUME"
  OUT="$RESUME"
  RUN_STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  SUFFIX="-$RUN_STAMP"
  REPORT="client-report-resume$SUFFIX.json"
  RESUMED_FROM="$RESUME"
else
  if [ -d "$OUT" ]; then
    if [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then
      die "--out directory is not empty: $OUT (use --resume to add a tagged attempt)"
    fi
  else
    mkdir -p "$OUT" || die "cannot create --out directory: $OUT"
  fi
  SUFFIX=""
  REPORT="client-report.json"
  RESUMED_FROM=""
fi

[ -e "$OUT/$REPORT" ] && die "refusing to overwrite existing $OUT/$REPORT (use --resume)"

RECORDS="$OUT/.phase-records$SUFFIX.jsonl"
: > "$RECORDS"

START_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
START_EPOCH="$(date +%s)"

phase_count=0
previous_end_epoch=""
while :; do
  now_epoch="$(date +%s)"
  elapsed=$(( now_epoch - START_EPOCH ))
  [ "$elapsed" -lt "$PLANNED_SECONDS" ] || break

  idx=$(( phase_count % 3 ))
  name="${PHASE_NAMES[$idx]}"
  ratio="${PHASE_RATIOS[$idx]}"
  remaining=$(( PLANNED_SECONDS - elapsed ))
  duration="$PHASE_SECONDS"
  [ "$duration" -le "$remaining" ] || duration="$remaining"
  [ "$duration" -ge 1 ] || break

  out_file="$OUT/$(printf 'phase-%04d%s.json' "$phase_count" "$SUFFIX")"
  argv=( "$PYTHON" -B "$GENERATOR" --host "$SERVER_HOST" --port "$PORT" --mode mixed
         --ratio "$ratio" --duration "$duration" --workers "$WORKERS"
         --connections-per-worker "$CONNECTIONS_PER_WORKER" --pipeline "$PIPELINE"
         --keys "$KEYS" --value-mix "$VALUE_MIX" --output "$out_file" )

  phase_start_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  phase_start_epoch="$(date +%s)"
  gap_seconds="null"
  if [ -n "$previous_end_epoch" ]; then
    gap_seconds=$(( phase_start_epoch - previous_end_epoch ))
  fi

  "${argv[@]}"
  rc=$?

  phase_end_epoch="$(date +%s)"
  phase_end_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  totals_json="$("$PYTHON" - "$out_file" <<'PYEOF' 2>/dev/null || true
import json, sys
try:
    report = json.load(open(sys.argv[1]))
    totals = report.get("totals") or {}
    print(json.dumps({
        "ops": totals.get("ops"), "sets": totals.get("sets"), "gets": totals.get("gets"),
        "errors": totals.get("errors"), "hitRatio": totals.get("hitRatio"),
        "opsPerSecond": totals.get("opsPerSecond"), "generatorExitCode": report.get("exitCode"),
    }))
except Exception as exc:  # noqa: BLE001
    print(json.dumps({"error": repr(exc)}))
PYEOF
)"
  [ -n "$totals_json" ] || totals_json='{"error":"unreadable"}'

  printf '{"phaseIndex":%d,"phase":"%s","ratio":"%s","exitCode":%d,"durationSeconds":%d,"phaseStartUtc":"%s","phaseEndUtc":"%s","phaseStartEpoch":%d,"phaseEndEpoch":%d,"gapSeconds":%s,"output":"%s","totals":%s}\n' \
    "$phase_count" "$name" "$ratio" "$rc" "$duration" "$phase_start_utc" "$phase_end_utc" \
    "$phase_start_epoch" "$phase_end_epoch" "$gap_seconds" "$(basename "$out_file")" \
    "$totals_json" >> "$RECORDS"

  previous_end_epoch="$phase_end_epoch"
  phase_count=$(( phase_count + 1 ))
done

END_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
END_EPOCH="$(date +%s)"

"$PYTHON" - "$RECORDS" "$OUT/$REPORT" "$START_UTC" "$END_UTC" "$START_EPOCH" "$END_EPOCH" \
  "$PLANNED_SECONDS" "$PHASE_SECONDS" "$SERVER_HOST" "$PORT" "$GENERATOR" "$RESUMED_FROM" <<'PYEOF'
import json, sys

(records_path, report_path, start_utc, end_utc, start_epoch, end_epoch, planned,
 phase_seconds, server_host, port, generator, resumed_from) = sys.argv[1:13]
planned = int(planned)
phase_seconds = int(phase_seconds)
start_epoch, end_epoch = int(start_epoch), int(end_epoch)
tolerance = 0.01

records = []
with open(records_path, "r", encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if line:
            records.append(json.loads(line))

failed_phases = [{"phaseIndex": r["phaseIndex"], "phase": r["phase"], "exitCode": r["exitCode"]}
                 for r in records if r.get("exitCode", 0) != 0]
gaps = []
for r in records:
    seconds = r.get("gapSeconds")
    if seconds is None:
        continue
    threshold = 3 * phase_seconds
    gaps.append({"afterPhaseIndex": r["phaseIndex"] - 1, "beforePhaseIndex": r["phaseIndex"],
                 "seconds": seconds, "thresholdSeconds": threshold, "exceedsThreshold": seconds > threshold})
exceeding = [g for g in gaps if g["exceedsThreshold"]]

observed = max(0, end_epoch - start_epoch)
covered = observed >= planned * (1 - tolerance)
reasons = []
if not covered:
    reasons.append("client_plan_not_covered")
if failed_phases:
    reasons.append("client_failed_phases")
if exceeding:
    reasons.append("client_phase_gaps")
if not records:
    reasons.append("client_no_phases")

if not records:
    status = "FAILED"
elif not covered or failed_phases or exceeding:
    status = "PARTIAL"
else:
    status = "COMPLETE"

report = {
    "schemaVersion": 1,
    "kind": "external-client-loop",
    "serverHost": server_host,
    "serverPort": int(port),
    "plannedSeconds": planned,
    "observedSeconds": observed,
    "phaseSeconds": phase_seconds,
    "phaseCount": len(records),
    "failedPhases": failed_phases,
    "gaps": gaps,
    "status": status,
    "reasons": reasons,
    "startUtc": start_utc,
    "endUtc": end_utc,
    "generatorPath": generator,
    "phaseCycle": ["mixed90_10", "get_only", "set_only"],
    "resumedFrom": resumed_from or None,
    "note": "phase artifacts are produced on the generator host; the server host "
            "externalTrafficGate must also be valid to evidence cross-machine load",
    "phases": records,
}
with open(report_path, "w", encoding="utf-8") as handle:
    json.dump(report, handle, indent=2)
print(report_path)
print("status=%s observed=%ds planned=%ds phases=%d failed=%d gaps=%d"
      % (status, observed, planned, len(records), len(failed_phases), len(exceeding)))
sys.exit(0 if status == "COMPLETE" else 1)
PYEOF

"""Correction-5 recorder using the unchanged correction-4 proof implementation."""
import reader_correction_audit as recorder

recorder.audit.OUT = recorder.audit.ROOT / 'tests/reports/release/perf-qual/correction-cycle/contract-correction-5-evidence'

if __name__ == '__main__':
    raise SystemExit(recorder.main())

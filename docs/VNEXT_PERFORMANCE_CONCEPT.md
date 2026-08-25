# VNext performance concept

This document records the post-stable-release performance investigation plan. It is intentionally separate from the first public stable release and must not change the 1.4.18-hookah.19 runtime behavior.

## Goal

Reduce any measurable GboardHookah-induced latency or process-pressure cost without broad system tuning or speculative hook removal.

## Investigation order

1. **Measurement first**
   - Establish a read-only baseline for Gboard input/clipboard interaction latency and process state.
   - Capture comparable A/B samples with GboardHookah enabled and disabled.
   - Change one variable at a time.

2. **Hook-cost instrumentation**
   - Measure invocation frequency and callback cost for the active clipboard/provider/SQLite/HashSet compatibility paths.
   - Optimize only paths proven to be hot or materially expensive.
   - Do not remove broad compatibility hooks merely because they look redundant in source.

3. **Optional Gboard-only process guard**
   - Only if scheduler/process-pressure evidence supports it, test a narrowly scoped Gboard-only process adjustment.
   - Historical starting hypothesis: `nice=-10`, `oom_score_adj=100`.
   - Treat these values as experimental parameters, not defaults.
   - No global LMKD/VM/ZRAM/governor/thermal tuning.

4. **Targeted thread fast-path last**
   - Consider only after measurement, hook-cost work, and any justified process-level test.
   - Scope to the specific proven hot thread/path and keep rollback trivial.

## A/B rules

- One parameter change per experiment.
- Keep Gboard build, GboardHookah build, clipboard configuration and test workload fixed within each comparison.
- Record before/after evidence sufficient to distinguish real improvement from noise.
- Reject changes that trade measurable stability or compatibility for marginal latency gains.

## Release separation

The first public stable release remains functionally frozen while this investigation starts. VNext work begins only after the stable-signed candidate has passed live Gboard/LSPosed acceptance and the public release is published.

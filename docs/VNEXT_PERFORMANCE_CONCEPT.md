# VNext performance concept

This document records the post-stable-release performance investigation plan. The stable `1.4.18-hookah.19` release gate is complete; VNext remains measurement-first and must not change stable runtime behavior without matched evidence.

Operational A/B details are in [`VNEXT_PHASE_AB_RUNBOOK.md`](VNEXT_PHASE_AB_RUNBOOK.md).

## Goal

Reduce any measurable GboardHookah-induced latency or process-pressure cost without broad system tuning or speculative hook removal.

## Investigation order

1. **Measurement first**
   - Establish a read-only enabled-state baseline for Gboard input/clipboard interaction and process pressure.
   - Capture a matched disabled-state baseline with GboardHookah activation as the only material changed variable.
   - Use the existing Heimnetz System Stability Observer for Android 17 pressure/process evidence where available.
   - Classify Memory Limiter/freezer exits so system pressure is not misattributed to Hookah overhead.
   - Change one variable at a time.

2. **Hook-cost instrumentation**
   - Measure invocation frequency and callback cost for the active clipboard/provider/SQLite/HashSet compatibility paths.
   - Optimize only paths proven to be hot or materially expensive.
   - Do not remove broad compatibility hooks merely because they look redundant in source.
   - Keep clipboard payload text out of instrumentation.

3. **Optional Gboard-only process guard**
   - Only if scheduler/process-pressure evidence supports it, test a narrowly scoped Gboard-only process adjustment.
   - Historical starting hypothesis: `nice=-10`, `oom_score_adj=100`.
   - Treat these values as experimental parameters, not defaults.
   - No global LMKD/VM/ZRAM/Memory-Limiter/freezer/governor/thermal tuning.

4. **Targeted thread fast-path last**
   - Consider only after measurement, hook-cost work, and any justified process-level test.
   - Scope to the specific proven hot thread/path and keep rollback trivial.

## A/B rules

- One parameter change per experiment.
- Keep Gboard build, GboardHookah build, clipboard configuration and test workload fixed within each comparison.
- Record before/after evidence sufficient to distinguish real improvement from noise.
- Android 17 Memory Limiter/freezer events are explicit confounders and must be recorded when present.
- Do not automate an LSPosed module toggle by editing LSPosed internal databases. Use a verified supported control surface if one exists; otherwise use a controlled UI transition and keep the measurement windows automated.
- Reject changes that trade measurable stability or compatibility for marginal latency gains.

## Clipboard truncation branch

The intermittent clipboard-content truncation report remains a separate content-safe VNext branch. Its first step is length-only boundary measurement: character count and UTF-8 byte count at Android ClipData, Gboard persistence/provider where measurable, and actual pasted output. Never log clipboard payload text.

## Release separation

The first public stable release remains functionally frozen. VNext runtime changes require their own evidence, review, live acceptance and release process; documentation or baseline collection does not imply a new release.

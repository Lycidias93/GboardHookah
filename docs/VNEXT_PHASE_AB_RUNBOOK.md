# GboardHookah VNext Phase A/B runbook

This runbook turns the VNext performance concept into an evidence-first comparison workflow. It does not change `1.4.18-hookah.19` runtime behavior by itself.

## Goal

Measure whether GboardHookah materially affects Gboard process pressure or latency before changing hooks, scheduler state, process priority, memory policy, or compatibility behavior.

Target package: `com.google.android.inputmethod.latin`.

Stable Hookah baseline: `1.4.18-hookah.19` / `29`.

## Measurement authority

The Heimnetz Pixel currently runs the read-only System Stability Observer from the single `chatgpt_stability_suite` module. It already samples Gboard together with Android 17 system pressure and records no clipboard payload content.

Use that observer as the system/process-pressure evidence source for the first A/B pair. Relevant fields include:

- PID continuity / process transitions;
- UID/process state and cached/frozen state;
- `oom_score_adj` and cgroup freeze state;
- RSS/PSS/SwapPss;
- cgroup memory and swap limits/events;
- Android memory/cpu/io PSI;
- zRAM/vmstat pressure context;
- Android 17 Memory Limiter state;
- `ApplicationExitInfo` evidence;
- GboardHookah build identity.

Android 17 Memory Limiter or freezer exits are comparison confounders and must be classified instead of being mistaken for Hookah callback overhead. Memory Limiter exits may expose `MemoryLimiter:AnonSwap`; freezer-related exits are tracked separately.

## Phase A — Hookah enabled

1. Keep the already live-accepted stable Hookah build enabled.
2. Keep the same Gboard build and clipboard configuration throughout the comparison.
3. Use normal organic keyboard/clipboard workload; do not force artificial process state transitions solely for the baseline.
4. Capture a bounded observer window with enough samples to include ordinary foreground/background transitions.
5. Record:
   - Gboard and Hookah versions;
   - measurement/boot window identity;
   - sample count;
   - PID transitions;
   - RSS/PSS/SwapPss distribution or bounded summary;
   - pressure/Memory Limiter/freezer events;
   - any observed reload/exit reason.

Phase A is evidence only. Do not tune process priority, scheduler, LMKD, VM, zRAM, Memory Limiter limits, freezer policy, thermal/governor state, or affinity during collection.

## Phase B — Hookah disabled

Phase B changes exactly one material variable: Hookah activation state.

- Keep Gboard build, device configuration, workload class and observer configuration matched to Phase A.
- Do not edit Hookah settings, hooks or system memory policy at the same time.
- Collect the same evidence fields and a comparable observation window.

### Activation-control rule

Do not modify LSPosed internal databases as an automation shortcut.

If the device exposes a verified supported LSPosed command surface capable of toggling this module safely, that surface can be used in a separately verified controller. Standard LSPosed must not be assumed to provide such a CLI. If no supported control surface is verified, the enabled/disabled transition remains a controlled explicit LSPosed UI operation followed by the required Gboard/process restart boundary. The measurement automation should still own evidence capture before and after the transition.

## Comparison

Compare distributions and event counts rather than one instantaneous snapshot. At minimum compare:

- process lifetime / PID-transition rate;
- CPU and top-thread CPU deltas where captured;
- RSS/PSS/SwapPss;
- major-fault and cgroup memory-event deltas;
- PSI pressure context;
- Memory Limiter/freezer exit attribution;
- user-visible latency observations only when they can be tied to a bounded measurement window.

A change is actionable only when it is materially larger than run-to-run noise and repeatable under a matched workload.

## Phase C — callback-cost instrumentation

Only after the matched A/B pair, add Hookah-internal counters/timing for active compatibility paths, including as applicable:

- provider legacy query path;
- provider Bundle query path;
- SQLite `query` paths;
- SQLite `rawQuery` paths;
- HashSet compatibility path;
- other hooks proven active by current runtime status.

Per-path instrumentation should record invocation count, cumulative callback duration and bounded maximum callback duration. Debug-disabled hot paths should avoid unnecessary allocation/string formatting. Clipboard payload text must never be logged.

## Optimization gate

Optimize only hooks that the matched evidence demonstrates to be hot or materially costly. Preserve compatibility hooks that are not proven harmful.

A Gboard-only process guard (`nice`, `oom_score_adj`, or similar) is an experiment of last resort after baseline and callback-cost evidence. Historical values such as `nice=-10` or `oom_score_adj=100` are hypotheses, not defaults. No global LMKD/VM/zRAM/thermal/governor tuning belongs to this workstream.

A thread-level fast path comes after process-level evidence and must target one specifically measured hot thread/path. Blanket RT scheduling or fixed affinity is out of scope.

## Clipboard truncation branch

Clipboard-content truncation remains separate from process-pressure performance work.

For that branch:

- never log clipboard text;
- record character and UTF-8 byte counts only;
- compare Android ClipData length, Gboard persistence/provider length where measurable, and actual pasted length;
- distinguish UI preview ellipsis from stored/pasted truncation;
- establish a repeatable boundary before changing Hookah runtime behavior.

## Acceptance

The first VNext runtime change is not justified merely because a candidate optimization exists. Acceptance requires a matched baseline, a specific measured cause, a narrowly scoped change, repeatable improvement, and no stability/compatibility regression.

# Threading plan — confining MatteModule / VideoFrameDecoder / VideoFrameEncoder

Same convention as `docs/ui-plan.md` and `docs/ui-redesign-plan.md`: each phase is a self-contained instruction that can be handed to a fresh Sonnet session on its own, followed by a check to run before starting the next.

---

## Is "one thread per object" correct practice?

**The instinct is right for these three classes, but not as a general rule, and not with raw `Thread`s.**

Doing it reflexively — every object gets a thread — is cargo-cult: each JVM thread costs a stack (~512KB–1MB reserved) plus scheduler pressure, and for a plain data-transforming class it buys nothing. What justifies it *here* is that all three classes wrap **stateful native resources that are explicitly not thread-safe**, and one of them has genuine thread affinity. So do it for the reason, not for the rule.

There are three separate motivations, and they are worth separating because only the first two are about correctness:

### 1. Correctness — the TFLite interpreter is not thread-safe, and the GPU delegate has thread affinity (strongest reason)

`Interpreter` is documented as not thread-safe: concurrent calls require external synchronization. Beyond that, the GPU delegate allocates an EGL context and GPU program objects when the interpreter is built, and those are bound to the thread that created them — using them from another thread is the classic source of intermittent EGL/`MediaCodec`-adjacent failures rather than clean, reproducible crashes.

**This project already violates that, today — measured, not inferred.** Both paths dispatch to `Dispatchers.Default`, which is a *pool*, and two separate `withContext(Dispatchers.Default)` dispatches carry **no same-thread guarantee**. Captured from this app's own logcat (PID `22934`, TID is the second numeric column):

```
01:33:31.047  22934 22970  loadModel: Using GPU            <- build starts on tid 22970
01:33:35.214  22934 22971  loadModel: Using GPU            <- a SECOND build starts on tid 22971,
01:33:38.477  22934 22970  loadModel: Interpreter Created     while the first is still running
01:33:41.128  22934 22971  loadModel: Interpreter Created
01:33:55.609  22934 22970  loadModel: Using GPU
01:34:02.610  22934 22970  loadModel: Interpreter Created  <- interpreter in use was built here
...
01:37:13.141  22934 22971  run: Model with multiple i/o executed in 605 ms   <- but invoked here
01:37:13.946  22934 22971  run: Model with multiple i/o executed in 568 ms
```

Two distinct defects are visible:

1. **Create-on-one-thread, invoke-on-another.** The live interpreter was built on tid `22970` and every subsequent inference runs on tid `22971`. This is the GPU-delegate affinity violation, confirmed. It works most of the time because the delegate tolerates it on many drivers — which is exactly the shape of the intermittent `Matting failed` seen once during UI Phase 3 testing (one fast failure, immediately succeeding on an identical rerun).
2. **Two overlapping interpreter builds** (`22970` and `22971`, 4 s apart, overlapping). `TFLiteModelRunner.configure` does `close()` then `loadModel()`; run concurrently, both build an interpreter and delegate, one wins the field assignment and the other's GPU delegate leaks.

Defect 2 was a TOCTOU race in `MatteViewModel.runConfigure` — `isConfiguring` was raised *inside* the launched coroutine, so `updateConfig`'s guard could still read `false` and start a second configure. **Already fixed** (flag now raised synchronously before `launch`, cleared in `finally`). Defect 1 is what this plan addresses: confining the module to one thread makes creation and invocation share a thread by construction, and additionally serialises any future overlapping `configure` calls through the executor's queue.

> This is the single best argument for the change, and it is now backed by thread IDs from a real run rather than by inference from the call sites.

### 2. Correctness — `MediaCodec` / `MediaMuxer` / `MediaMetadataRetriever` are not thread-safe

`VideoFrameEncoder` owns a `MediaCodec` + `MediaMuxer` pair; `VideoFrameDecoder` owns a `MediaMetadataRetriever`. None may be driven from two threads concurrently. **Today there is no bug**, because `Controller.matteVideo` drives all of them from one sequential coroutine. But that safety is incidental — it depends on the caller, not on the class. Confinement makes each class safe by construction, which is what makes Phase 2 (below) possible at all.

### 3. Performance — pipelining (the actual payoff, but only with Phase 2)

Measured from this app's own logcat during a 213-frame run:

| Stage | Per frame |
| --- | --- |
| `VideoFrameDecoder.getNextFrame` | 124–234 ms |
| `TFLiteModelRunner.run` (inference) | 470–600 ms |
| `VideoFrameEncoder.putNextFrame` × 2 | ~30 ms each |
| **`Controller` total per frame** | **674–787 ms** |

Today these run strictly in series. With decode / inference / encode on separate threads and frames flowing between them, throughput becomes bounded by the slowest stage (inference, ~480 ms) rather than their sum, i.e. roughly **1.5× faster** (~730 ms → ~490 ms per frame; a 213-frame clip goes from ~180 s to ~105 s).

**Important:** thread confinement *alone* (Phase 1) delivers none of this. It moves each object's work onto its own thread but the caller still blocks on each call in turn, so the wall-clock is unchanged. The speedup only arrives when you also restructure into producer/consumer stages (Phase 2).

### Verdict

- **Phase 1 (confinement): do it.** Small, surgical, fixes a real latent correctness bug, no behaviour change.
- **Phase 2 (pipelining): optional.** Real ~1.5× win, but it introduces buffer-lifetime complexity that can silently corrupt output if done carelessly (see §"Buffer aliasing" below). Do it as a separate, independently revertable change, and only if runtime actually matters to you.
- **Consider the decoder rewrite first** if speed is the real goal — see "A faster alternative to Phase 2" at the end. It is likely a bigger win for less risk.

---

## Key decision 1: single-threaded *dispatchers/executors*, not raw `Thread` objects

Do **not** write `Thread { … }.start()` and hand-roll a work queue. Use one `Executors.newSingleThreadExecutor()` per object, created in the constructor and shut down in `close()`. Reasons:

- It *is* the "dedicated thread" you want — a single-thread executor is backed by exactly one thread for its whole lifetime, so every task on it runs on the same thread. That gives the affinity guarantee from §1 directly.
- It already has the work queue, ordering guarantee, and shutdown semantics you would otherwise reimplement badly.
- It converts to a coroutine dispatcher for free (`.asCoroutineDispatcher()`) if Phase 2 goes ahead.
- Named threads (`rvm-matte`, `rvm-decode`, `rvm-encode-matte`, `rvm-encode-fgr`) make logcat and ANR traces instantly readable, which raw anonymous threads do not.

**Blocking vs suspend:** for Phase 1, keep the existing non-suspend interface signatures (`configure`/`run`/`reset`/`close`) and submit-and-block internally (`executor.submit(…).get()`). The caller is already a background coroutine that blocks on these calls today, so nothing regresses, and `ModuleInterface` / `VideoFrameEncoderInterface` / `VideoFrameDecoderInterface` stay untouched. Converting the interfaces to `suspend` is a much larger diff and is only worth it as part of Phase 2.

## Key decision 2: the three things that will bite

These are the failure modes to design against. The first is by far the most dangerous.

### Buffer aliasing (Phase 2 only — but fatal if missed)

`Controller.matteVideo` allocates **one** `inputFrameBuffer`, **one** `outputFgrBuffer`, and **one** `outputMatteBuffer` (`Controller.kt`, `SharedBuffer` allocations before the loop) and reuses all three on every iteration. That is correct *only* because the stages are serialised. The moment decode and inference overlap, the decoder writes frame N+1 into `inputFrameBuffer` while inference is still reading frame N out of it → silently corrupted output, no exception. Phase 2 therefore **must** introduce a small pool of buffer slots with explicit ownership handoff; it is not optional.

### The model is recurrent — inference cannot be parallelised across frames

`MatteModule.runRVM` reads `hiddenStates`, runs, then copies the fresh states back in for the next call. Frame N+1's inference depends on frame N's output. So:

- There is exactly **one** inference thread, always. Never a pool.
- Frames must reach the inference stage **in order**.
- Frames must reach each encoder **in order** (`VideoFrameEncoder` increments `presentationTimeUs` internally per call).

Pipelining across *stages* is fine; parallelism *within* the inference stage is not.

### Re-entrancy deadlock (Phase 1 — easy to introduce, easy to avoid)

A single-thread executor deadlocks if a task running on it submits another task to the same executor and blocks on the result. Two existing methods do exactly this internally:

- `MatteModule.configure()` calls `reset()` (`MatteModule.kt:39`)
- `VideoFrameEncoder.putNextFrames()` calls `putNextFrame()` (`VideoFrameEncoder.kt:110`)

If both the outer and inner method are wrapped, the outer blocks forever waiting for the inner, which can never be scheduled. **Fix: wrap only the public entry points, and have them delegate to private unwrapped `…Impl` methods that call each other directly.** Do not use "am I already on the right thread?" name-sniffing — it works but it is fragile and hides the structure.

---

## Phase 1 — Confine each object to its own thread

**Goal:** every call into a `MatteModule`, `VideoFrameDecoder`, or `VideoFrameEncoder` instance executes on that instance's own dedicated thread. No behaviour change, no measurable speed change; this is a correctness and structure change only.

**Instruction to give:**

> Add a tiny shared helper, then apply the same pattern to three classes.
>
> **1. Create `app/src/main/java/dev/hamster/rvm/utils/ConfinedRunner.kt`:**
> ```kotlin
> package dev.hamster.rvm.utils
>
> import java.util.concurrent.Callable
> import java.util.concurrent.ExecutionException
> import java.util.concurrent.Executors
> import java.util.concurrent.TimeUnit
>
> /**
>  * Confines all work for one object to a single dedicated thread.
>  *
>  * Exists because the objects using it wrap native resources that are not thread-safe, and in
>  * TFLite's case are bound to the thread that created them (the GPU delegate's EGL context).
>  * Dispatching those objects' work through `Dispatchers.Default` gives no same-thread guarantee,
>  * since that dispatcher is a pool.
>  *
>  * Callers block until the submitted work completes, exactly as they did when the work ran
>  * inline - this class changes *which* thread runs the work, not the calling convention.
>  */
> class ConfinedRunner(threadName: String) {
>
>     private val executor = Executors.newSingleThreadExecutor { runnable ->
>         Thread(runnable, threadName)
>     }
>
>     /**
>      * Runs [block] on the confined thread and waits for it.
>      *
>      * Must never be called from the confined thread itself: this is a single-thread executor, so
>      * submitting from it and blocking on the result deadlocks. Public entry points wrap; the
>      * private implementations they delegate to must call each other directly, unwrapped.
>      */
>     fun <T> run(block: () -> T): T =
>         try {
>             executor.submit(Callable { block() }).get()
>         } catch (e: ExecutionException) {
>             // Unwrap so callers still see the original exception. MatteViewModel surfaces
>             // `e.message` straight into the UI's error snackbar, and an un-unwrapped
>             // ExecutionException would turn a useful message into a stack-trace class name.
>             throw e.cause ?: e
>         }
>
>     /** Shuts the thread down. Call from the owner's `close()`; the runner is unusable afterwards. */
>     fun shutdown() {
>         executor.shutdown()
>         if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
>             executor.shutdownNow()
>         }
>     }
> }
> ```
>
> **2. `MatteModule.kt`** — add `private val runner = ConfinedRunner("rvm-matte")`. Rename the existing bodies of `configure`, `run`, `reset` and `close` to private `configureImpl`, `runImpl`, `resetImpl`, `closeImpl`, then make the four `override` methods thin wrappers:
> ```kotlin
> override fun configure(newConfig: MatteConfig) = runner.run { configureImpl(newConfig) }
> override fun run(io: MatteIO, count: Int) = runner.run { runImpl(io, count) }
> override fun reset() = runner.run { resetImpl() }
> override fun close() = runner.run { closeImpl() }.also { runner.shutdown() }
> ```
> **Critical:** inside `configureImpl`, change the existing `reset()` call (`MatteModule.kt:39`) to `resetImpl()`. Leaving it as `reset()` deadlocks the module thread on the first reconfigure — the outer task would block waiting for an inner task that the single-thread executor cannot schedule until the outer one returns.
> Note `runner.shutdown()` is called *after* `closeImpl()` completes, so the interpreter and hidden states are released on the same thread that created them — which is the entire point of the change.
>
> **3. `VideoFrameEncoder.kt`** — the class currently takes no constructor arguments and `Controller` creates two of them, so give it a name to distinguish the two threads in logcat: `class VideoFrameEncoder(threadName: String = "rvm-encode")`, with `private val runner = ConfinedRunner(threadName)`. Apply the same wrapper/impl split to `startVideoEncoder`, `putNextFrame`, `putNextFrames`, and `saveVideo`.
> **Critical:** inside `putNextFramesImpl`, change the `putNextFrame(...)` call (`VideoFrameEncoder.kt:110`) to `putNextFrameImpl(...)` — same deadlock as above.
> `saveVideo()` already releases the codec and muxer, so make it `runner.run { saveVideoImpl() }.also { runner.shutdown() }`. Because that shuts the runner down, add a guard: a second `saveVideo()` call on the same instance should throw a clear `IllegalStateException("Encoder already saved")` rather than a `RejectedExecutionException`. In `Controller`, construct them as `VideoFrameEncoder("rvm-encode-matte")` and `VideoFrameEncoder("rvm-encode-fgr")`.
>
> **4. `VideoFrameDecoder.kt`** — add `private val runner = ConfinedRunner("rvm-decode")` and wrap **only** `startVideoDecoder`, `getNextFrame`, and `getNextFrames`. Do **not** wrap `getHeight()`, `getWidth()`, `getFrameCount()`, `getFps()`, or `getBitrate()`: those only read Kotlin fields that are written once during `startVideoDecoder` and read afterwards, so they carry no native-object affinity, and wrapping them would add a pointless thread hop (and a deadlock risk) to every call.
>
> **5. Fix the decoder's missing cleanup while you are here.** `MediaMetadataRetriever` is created in `VideoFrameDecoder` but never released — a real pre-existing leak, made more visible now that the class also owns a thread. Add to `VideoFrameDecoderInterface`:
> ```kotlin
> /** Releases the underlying extractor/retriever and the decoder's thread. Unusable afterwards. */
> fun close()
> ```
> and implement it as `runner.run { retriever.release() }.also { runner.shutdown() }`.
>
> **6. `Controller.kt`** — `close()` currently only closes `mattingModule`, so the decoder's and encoders' threads would leak for the life of the process. Extend it:
> ```kotlin
> fun close() {
>     mattingModule.close()
>     videoDecoder.close()
> }
> ```
> Do **not** call `close()`/`saveVideo()` on the two encoders here: they are already finalised by `saveVideo()` at the end of each `matteVideo()` run, and an encoder that was never started has no codec to release. If a run is cancelled mid-way the encoders are left un-finalised — that is a pre-existing leak on the cancel path, out of scope for this phase; note it and move on.

**Acceptance:** the app builds and behaves exactly as before — import, matte, switch outputs, save, reset all work, and a full run produces byte-comparable output. In logcat, `TFLiteModelRunner`'s `loadModel: Interpreter Created` and its subsequent `run: Model with multiple i/o executed` lines now share the **same TID** (second numeric column), where previously they differed; `VideoFrameDecoder` lines all share one TID and each `VideoFrameEncoder` its own. Per-frame timing is unchanged (~700–800 ms) — this phase is not a speed change. Run two matting passes back to back and confirm no deadlock on the second `configure`.

> **Verified against a real build.** All of the above held: `Interpreter Created` and every `run:` line shared one TID; `VideoFrameDecoder` used one TID throughout; the two `VideoFrameEncoder`s used two distinct TIDs; two back-to-back matting runs both completed (147.8s/693ms-per-frame and 155.8s/731ms-per-frame — no regression) with no `RejectedExecutionException` and no deadlock, confirming the `saveVideo()`/`close()` split above (§ "Key decision 1" note) actually matters, not just in theory.
>
> **One surprise worth recording so it isn't mistaken for a leak later:** `ps -T -p <pid> | grep rvm-matte` shows **4** threads, not 1. Traced with a temporary identity-hash diagnostic in `MatteModule`'s constructor: only **one** `MatteModule` is ever constructed (single stack trace, single identity hash) - `ConfinedRunner` is not the source. Cross-checked by changing the thread-count slider and reconfiguring: the *first* TID stays constant across reconfigures (that's `ConfinedRunner`'s one Java-level executor thread, correctly reused), while the other 3 TIDs change together each time the interpreter is rebuilt. Those 3 are TFLite's own native GPU-delegate worker pool - spawned from, and inheriting the Linux `comm` name of, whichever thread builds the interpreter. Before this phase they'd have inherited an anonymous `DefaultDispatcher-worker-N` name and been indistinguishable from real dispatcher threads; confining creation to a thread named `rvm-matte` just makes them visible under that name too. Not a bug, not something `ConfinedRunner.shutdown()` needs to manage (they're released when `MatteModule.close()` releases the interpreter/delegate) - noted here only so a future `ps -T` check doesn't get mistaken for 3 leaked threads.

---

## Phase 2 — Pipeline the stages (optional)

**Goal:** overlap decode / inference / encode so per-frame wall-clock drops from their sum (~730 ms) toward the slowest stage (~480 ms). **Do not start this until Phase 1 is landed and verified.**

**Instruction to give:**

> Restructure `Controller.matteVideo` into three concurrent stages connected by channels, with explicit buffer ownership. Everything below lives inside `matteVideo`; the module/encoder/decoder classes are unchanged from Phase 1.
>
> **1. Convert the confined runners to dispatchers.** Add to `ConfinedRunner`:
> ```kotlin
> val dispatcher: CoroutineDispatcher by lazy { executor.asCoroutineDispatcher() }
> ```
> and expose each object's dispatcher (e.g. `MatteModule.dispatcher`). Stage coroutines then `withContext(module.dispatcher) { … }` instead of blocking, so a stage waiting on its thread does not also pin a `Dispatchers.Default` worker.
>
> **2. Introduce a frame-slot pool — this is the part that must not be skipped.** Define a slot holding one frame's three buffers:
> ```kotlin
> private class FrameSlot(height: Int, width: Int) {
>     val input = SharedBuffer(height * width * 3 * 4)
>     val fgr = SharedBuffer(height * width * 3 * 4)
>     val matte = SharedBuffer(height * width * 4)
>     fun close() { input.clear(); fgr.clear(); matte.clear() }
> }
> ```
> Allocate exactly **3** slots (one in flight per stage) and circulate them through a `Channel<FrameSlot>` used as a free-list. Three is the right number: fewer starves the pipeline, more just consumes memory — at 720×1280 each slot is ~17 MB, so 3 slots ≈ 51 MB, which is already significant and should not be raised casually.
>
> The ownership rule is absolute: **a stage may touch a slot's buffers only between receiving it and sending it on.** Never keep a reference after sending.
>
> **3. Wire the stages** inside a `coroutineScope { … }` so a failure or cancellation in any stage tears down the others:
> ```
> freeSlots:   Channel<FrameSlot>(capacity = 3), pre-filled with the 3 slots
> decoded:     Channel<FrameSlot>(capacity = 1)
> inferred:    Channel<FrameSlot>(capacity = 1)
>
> decode stage:     repeat(frames) { slot = freeSlots.receive(); decode into slot.input; decoded.send(slot) }
>                   then decoded.close()
> inference stage:  for (slot in decoded) { run model: slot.input -> slot.fgr + slot.matte; inferred.send(slot) }
>                   then inferred.close()
> encode stage:     for (slot in inferred) { matteEncoder.putNextFrame(slot.matte, 1, 255f)
>                                            fgrEncoder.putNextFrame(slot.fgr, 3)
>                                            onProgress(...); freeSlots.send(slot) }
> ```
> Channels preserve FIFO order, which is what satisfies both ordering constraints (recurrent hidden state, and monotonic `presentationTimeUs` per encoder). Keep the two encoder calls in the *same* stage rather than splitting them into two parallel stages: they are ~30 ms each against a ~480 ms inference, so splitting buys nothing and doubles the ownership bookkeeping.
>
> **4. Preserve cancellation.** Replace the current `coroutineContext.ensureActive()` in the frame loop with an `ensureActive()` at the top of each stage's loop body. Because all three stages are children of one `coroutineScope`, `runningJob.cancel()` from `MatteViewModel.cancel()` still tears the whole pipeline down.
>
> **5. Report progress from the encode stage only** (the last stage), so the progress bar reflects frames actually written, not frames merely decoded — otherwise it races ahead and then stalls at 100% while the tail drains.
>
> **6. Close every slot** in a `finally` block, and only after all three stages have joined.

**Acceptance:** output videos are **visually identical** to a Phase 1 run of the same clip at the same config — check the matte and the foreground frame by frame at a few timestamps, since buffer-aliasing corruption shows up as content from the wrong frame rather than as an error. Per-frame time drops to roughly the inference time (~480–550 ms); a 213-frame clip should complete in ~110 s instead of ~180 s. Cancel mid-run and confirm no thread or memory leak and that a subsequent run still succeeds. Confirm peak memory stays acceptable (3 slots ≈ 51 MB on top of the model).

---

## A faster alternative to Phase 2, worth considering first

If the real goal is speed rather than structure, the decoder is the more promising target, and it is a smaller change than the pipeline.

`VideoFrameDecoder.getNextFrame` calls `retriever.getFrameAtTime(index * frameDurationUs, OPTION_CLOSEST)` — a **random-access seek per frame**, on a codec that is designed to decode sequentially. That is why decode costs 124–234 ms per frame for what is, in a streaming decoder, closer to a 5–15 ms operation. Replacing it with a `MediaCodec` + `MediaExtractor` streaming loop that decodes frames in order would plausibly cut decode from ~180 ms to ~10 ms, taking the per-frame total from ~730 ms to ~560 ms — comparable to what the whole Phase 2 pipeline achieves, without any buffer-ownership risk.

The two compose: streaming decode **and** pipelining would put per-frame time at the inference floor (~480 ms). But if only one is going to be done, the decoder rewrite is the better risk-adjusted trade. It is out of scope for this document; noted so the sequencing decision is made deliberately.

---

## Summary

| Phase | Delivers | Risk | Recommendation |
| --- | --- | --- | --- |
| 1 — Confinement | Fixes cross-thread GPU-delegate/interpreter use; makes the media classes safe by construction; plugs the `MediaMetadataRetriever` leak | Low — mechanical, main hazard is the two re-entrancy deadlocks, both called out | **Do it** |
| 2 — Pipelining | ~1.5× throughput (~730 → ~490 ms/frame) | Medium — buffer aliasing corrupts output silently if the slot pool is done wrong | Optional; only if runtime matters |
| (alt) Streaming decoder | Possibly ~1.3× on its own, composes with Phase 2 | Medium, but no shared-buffer hazard | Consider **before** Phase 2 |

### Critical files

- `app/src/main/java/dev/hamster/rvm/utils/ConfinedRunner.kt` *(new, Phase 1)*
- `app/src/main/java/dev/hamster/rvm/matte/MatteModule.kt` — note the `reset()` → `resetImpl()` change at line 39
- `app/src/main/java/dev/hamster/rvm/video/VideoFrameEncoder.kt` — note the `putNextFrame` → `putNextFrameImpl` change at line 110
- `app/src/main/java/dev/hamster/rvm/video/VideoFrameDecoder.kt` + `VideoFrameDecoderInterface.kt` — gains `close()`
- `app/src/main/java/dev/hamster/rvm/Controller.kt` — named encoders, extended `close()`, and the whole of Phase 2

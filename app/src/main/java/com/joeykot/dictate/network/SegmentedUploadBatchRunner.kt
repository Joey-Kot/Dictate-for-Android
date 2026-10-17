package com.joeykot.dictate.network

import com.joeykot.dictate.model.SegmentedUploadConfig
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Executes complete, independently-addressable transcription operations for a
 * prepared audio-segment batch.
 *
 * This deliberately owns only dispatch semantics.  Callers retain ownership
 * of their parent-job cancellation source and use [cancelInFlight] to cancel
 * work that has already started. That separation lets terminal failures stay
 * at their source position while a real parent cancellation takes priority
 * over every completed result.
 */
class SegmentedUploadBatchRunner(
    private val executorFactory: (Int) -> ExecutorService = { concurrency ->
        Executors.newFixedThreadPool(concurrency, SegmentThreadFactory)
    },
) {
    /**
     * [index] is retained in failure results and diagnostics.  The list order,
     * rather than the numeric value of [index], defines the transcript order.
     */
    data class Item<out T>(
        val index: Int,
        val value: T,
    )

    /** The terminal result produced by one complete segment workflow. */
    sealed interface TaskResult<out Failure> {
        data class Success(val text: String) : TaskResult<Nothing>

        data class Failure<Failure>(val error: Failure) : TaskResult<Failure>

        data object Cancelled : TaskResult<Nothing>
    }

    /** One terminal outcome, retained in original source-segment order. */
    data class Outcome<out Failure>(
        val index: Int,
        val result: TaskResult<Failure>,
    )

    /** The terminal result for the entire segment batch. */
    sealed interface Result<out Failure> {
        /** Every segment completed; callers preserve failures at their source position. */
        data class Completed<Failure>(val outcomes: List<Outcome<Failure>>) : Result<Failure>

        /** A task escaped its expected result boundary. */
        data class UnexpectedFailure(
            val index: Int,
            val cause: Throwable,
        ) : Result<Nothing>

        /** The parent requested cancellation, or a segment was cancelled first. */
        data object Cancelled : Result<Nothing>

        data object Empty : Result<Nothing>

        data object InvalidConcurrency : Result<Nothing>
    }

    /**
     * Runs at most [maxConcurrency] complete segment workflows at a time.
     *
     * A terminal segment failure is retained alongside successful siblings,
     * and work continues within [maxConcurrency]. Only cancellation and
     * escaped task failures stop dispatch. A true parent cancellation wins
     * over completed results.
     *
     * [isParentCancellationRequested] must describe only the parent job.  Do
     * not point it at an internal child source that [cancelInFlight] cancels
     * for any reason, otherwise ordinary work could be misreported as a user
     * cancellation.
     */
    fun <Input, Failure> run(
        items: List<Item<Input>>,
        maxConcurrency: Int,
        isParentCancellationRequested: () -> Boolean,
        cancelInFlight: () -> Unit,
        execute: (Item<Input>) -> TaskResult<Failure>,
    ): Result<Failure> {
        if (items.isEmpty()) return Result.Empty
        // Settings validation normally prevents this. Keep the dispatch
        // boundary defensive because frozen retry metadata and direct callers
        // must never turn an imported number into an unbounded thread pool.
        if (maxConcurrency !in 1..SegmentedUploadConfig.MAX_CONCURRENCY) {
            return Result.InvalidConcurrency
        }

        val executor = executorFactory(maxConcurrency)
        val completions = ExecutorCompletionService<Completed<Failure>>(executor)
        val cancellationSignalled = AtomicBoolean(false)
        val outcomes = arrayOfNulls<Outcome<Failure>>(items.size)
        var nextItem = 0
        var inFlight = 0
        var stopped = false
        var taskCancelled = false
        var interrupted = false
        var unexpectedFailure: Result.UnexpectedFailure? = null

        fun stopAndCancelInFlight() {
            stopped = true
            if (cancellationSignalled.compareAndSet(false, true)) {
                runCatching(cancelInFlight)
            }
        }

        fun observeParentCancellation() {
            if (isParentCancellationRequested()) stopAndCancelInFlight()
        }

        try {
            observeParentCancellation()
            while (nextItem < items.size || inFlight > 0) {
                observeParentCancellation()
                while (!stopped && inFlight < maxConcurrency && nextItem < items.size) {
                    observeParentCancellation()
                    if (stopped) break

                    val listPosition = nextItem++
                    val item = items[listPosition]
                    try {
                        completions.submit(Callable {
                            try {
                                Completed(
                                    listPosition = listPosition,
                                    itemIndex = item.index,
                                    result = execute(item),
                                )
                            } catch (error: Throwable) {
                                Completed(
                                    listPosition = listPosition,
                                    itemIndex = item.index,
                                    escapedError = error,
                                )
                            }
                        })
                        inFlight += 1
                    } catch (error: Throwable) {
                        unexpectedFailure = unexpectedFailure
                            ?: Result.UnexpectedFailure(item.index, error)
                        stopAndCancelInFlight()
                    }
                }

                if (inFlight == 0) {
                    if (stopped) break
                    continue
                }

                val completed = try {
                    completions.poll(COMPLETION_POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    // Clear the interrupt while draining, then restore it
                    // immediately before returning to the caller.
                    Thread.interrupted()
                    stopAndCancelInFlight()
                    null
                }
                if (completed == null) continue

                var outcome: Completed<Failure>? = null
                while (outcome == null) {
                    outcome = try {
                        completed.get()
                    } catch (_: InterruptedException) {
                        interrupted = true
                        Thread.interrupted()
                        stopAndCancelInFlight()
                        // This future has already completed.  Retry its get
                        // after clearing the interrupt so it still counts
                        // toward the required drain.
                        null
                    } catch (error: Throwable) {
                        // Callable catches all expected task exceptions.  Keep
                        // this defensive branch for executor implementation
                        // bugs and treat it as an escaped task failure.
                        unexpectedFailure = unexpectedFailure
                            ?: Result.UnexpectedFailure(-1, error.cause ?: error)
                        stopAndCancelInFlight()
                        Completed(
                            listPosition = -1,
                            itemIndex = -1,
                            escapedError = error.cause ?: error,
                        )
                    }
                }
                inFlight -= 1
                val resolvedOutcome = requireNotNull(outcome)

                val escaped = resolvedOutcome.escapedError
                if (escaped != null) {
                    if (unexpectedFailure == null) {
                        unexpectedFailure = Result.UnexpectedFailure(resolvedOutcome.itemIndex, escaped)
                    }
                    stopAndCancelInFlight()
                    continue
                }

                when (val result = requireNotNull(resolvedOutcome.result)) {
                    is TaskResult.Success -> {
                        outcomes[resolvedOutcome.listPosition] = Outcome(resolvedOutcome.itemIndex, result)
                    }

                    is TaskResult.Failure -> {
                        outcomes[resolvedOutcome.listPosition] = Outcome(resolvedOutcome.itemIndex, result)
                    }

                    TaskResult.Cancelled -> {
                        // A sibling may finish its cancellation before the
                        // original failed task returns. Keep it provisional:
                        // a later real failure still wins unless the parent
                        // itself has requested cancellation.
                        taskCancelled = true
                        stopAndCancelInFlight()
                    }
                }
            }
        } finally {
            executor.shutdown()
            if (interrupted) Thread.currentThread().interrupt()
        }

        // The parent source remains distinct from the internal batch source,
        // so this check correctly gives a real user cancellation precedence.
        if (isParentCancellationRequested() || interrupted) return Result.Cancelled
        unexpectedFailure?.let { return it }
        if (taskCancelled) return Result.Cancelled

        return Result.Completed(outcomes.map { requireNotNull(it) })
    }

    private data class Completed<Failure>(
        val listPosition: Int,
        val itemIndex: Int,
        val result: TaskResult<Failure>? = null,
        val escapedError: Throwable? = null,
    )

    private companion object {
        const val COMPLETION_POLL_MILLIS = 50L

        val SegmentThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "dictate-segmented-upload-${nextThreadId.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        val nextThreadId = AtomicLong()
    }
}

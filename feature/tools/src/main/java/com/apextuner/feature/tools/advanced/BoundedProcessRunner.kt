package com.apextuner.feature.tools.advanced

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

internal data class BoundedProcessResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

/** Runs only caller-supplied internal command arrays with bounded output, input, and lifetime. */
internal object BoundedProcessRunner {
    fun run(
        arguments: List<String>,
        timeoutMillis: Long,
        maximumOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        stdin: ByteArray? = null,
    ): BoundedProcessResult {
        if (!isStructurallySafe(arguments) || stdin?.size?.let { it > MAX_INPUT_BYTES } == true) {
            return BoundedProcessResult(-1, "ERROR: invalid internal command", false)
        }
        val timeout = timeoutMillis.coerceIn(1_000L, 15_000L)
        val outputLimit = maximumOutputBytes.coerceIn(1_024, 128 * 1024)
        var process: Process? = null
        var reader: Thread? = null
        val output = ByteArrayOutputStream(minOf(outputLimit, 8 * 1024))
        try {
            process = ProcessBuilder(arguments).redirectErrorStream(true).start()
            val startedProcess = process
            if (stdin != null) {
                startedProcess.outputStream.use { stream ->
                    stream.write(stdin)
                    stream.flush()
                }
            } else {
                startedProcess.outputStream.close()
            }
            reader = Thread({
                startedProcess.inputStream.use { input ->
                    val buffer = ByteArray(4_096)
                    var retained = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (retained < outputLimit) {
                            val keep = minOf(count, outputLimit - retained)
                            output.write(buffer, 0, keep)
                            retained += keep
                        }
                    }
                }
            }, "ApexTunerBoundedProcessOutput").apply { isDaemon = true }
            reader.start()

            val completed = startedProcess.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!completed) {
                terminate(startedProcess)
                reader.join(READER_JOIN_MILLIS)
                return BoundedProcessResult(-1, boundedOutput(output.toString(Charsets.UTF_8.name())), true)
            }
            reader.join(READER_JOIN_MILLIS)
            return BoundedProcessResult(
                exitCode = startedProcess.exitValue(),
                output = boundedOutput(output.toString(Charsets.UTF_8.name())),
                timedOut = false,
            )
        } catch (interrupted: InterruptedException) {
            process?.let(::terminate)
            reader?.interrupt()
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (error: Throwable) {
            process?.let(::terminate)
            return BoundedProcessResult(-1, "ERROR: ${error.javaClass.simpleName}", false)
        } finally {
            process?.takeIf { it.isAlive }?.let(::terminate)
        }
    }

    fun isStructurallySafe(arguments: List<String>): Boolean =
        arguments.isNotEmpty() && arguments.size <= MAX_ARGUMENTS && arguments.all { argument ->
            argument.length in 1..MAX_ARGUMENT_LENGTH && argument.none { it == '\n' || it == '\r' || it == '\u0000' }
        }

    private fun terminate(process: Process) {
        runCatching { process.destroy() }
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    private const val MAX_ARGUMENTS = 16
    private const val MAX_ARGUMENT_LENGTH = 2_048
    private const val MAX_INPUT_BYTES = 4_096
    private const val DEFAULT_MAX_OUTPUT_BYTES = 32 * 1024
    private const val READER_JOIN_MILLIS = 1_000L
}

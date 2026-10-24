package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import com.eclipsesource.v8.V8
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CacheService
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.common.withLock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Solves the n-parameter challenge for TCL-flavored players (tv-player-*-tcl.vflset), whose
 * n-function has a different call shape than the regular web/TV player and isn't recognized by
 * the generic (yt-dlp ejs) [V8ChallengeProvider].
 *
 * Unlike the regular player, the TCL n-function is invoked with a full URL object (it mutates the
 * "n" query param in place) rather than the bare n-param string — [solveN] hides that behind the
 * same bare-nParam contract the rest of the codebase uses, by wrapping/unwrapping a synthetic
 * placeholder URL.
 */
internal object TclChallengeProvider {
    private val tag = TclChallengeProvider::class.simpleName
    private const val libPrefix = "nsigsolver/"
    private val libFilenames = listOf(
        "${libPrefix}polyfill.js",
        "${libPrefix}meriyah-6.1.4.min.js",
        "${libPrefix}astring-1.9.0.min.js",
        "${libPrefix}tcl.solver.js"
    )
    private val solverOutputType = object : TypeToken<SolverOutput>() {}.type
    private var v8Runtime: V8? = null
    private val v8Lock = Any()
    // V8 must be created, used and released on the same thread.
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "TclChallengeSolver").apply { isDaemon = true }
    }
    private var idleCleanup: ScheduledFuture<*>? = null
    private var cachedPlayerUrl: String? = null
    private var cachedPlayerCode: String? = null
    private const val cacheSection = "tcl-challenge-solver"

    private data class SolverOutput(val type: String?, val results: Map<String, String>?, val error: String?, val preprocessed_player: String?)

    /**
     * @param playerUrl the TCL player's url (cache key)
     * @param playerCode lazily loads the TCL player source when no processed cache exists
     * @param nParams bare n-parameter values (as seen in a stream url's "n" query param)
     * @return map from each input nParam to its decoded value, or null for one that failed
     */
    fun solveN(playerUrl: String, playerCode: () -> String?, nParams: List<String>): Map<String, String?> {
        val task = worker.submit<Map<String, String?>> {
            idleCleanup?.cancel(false)
            try {
                solveNOnWorker(playerUrl, playerCode, nParams)
            } finally {
                // Reuse the decoder across videos, but release it on idle TVs.
                idleCleanup = worker.schedule({
                    shutdownIfNeeded()
                    cachedPlayerUrl = null
                    cachedPlayerCode = null
                }, 30, TimeUnit.SECONDS)
            }
        }
        return try {
            task.get()
        } catch (e: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun solveNOnWorker(playerUrl: String, playerCode: () -> String?, nParams: List<String>): Map<String, String?> {
        if (nParams.isEmpty()) {
            return emptyMap()
        }

        // n-params are opaque URL-safe tokens; wrap each in a throwaway url so the TCL
        // n-function (which expects a real URL object, not a bare string) has something valid
        // to parse. The wrapper's own shape doesn't matter beyond being a well-formed https url.
        val challengeByParam = nParams.distinct().associateWith { "https://www.youtube.com/videoplayback?n=$it" }

        val cacheKey = "player:$playerUrl"
        val cachedPreprocessed = if (cachedPlayerUrl == playerUrl) cachedPlayerCode
            else CacheService.load(cacheSection, cacheKey)?.code
        if (cachedPlayerUrl != playerUrl) {
            shutdownIfNeeded()
        }
        cachedPlayerUrl = playerUrl
        cachedPlayerCode = cachedPreprocessed

        val request = if (cachedPreprocessed != null) {
            mapOf(
                "type" to "preprocessed",
                "preprocessed_player" to cachedPreprocessed,
                "challenges" to challengeByParam.values.toList()
            )
        } else {
            mapOf(
                "type" to "player",
                "player" to (playerCode() ?: return nParams.associateWith { null }),
                "challenges" to challengeByParam.values.toList(),
                "output_preprocessed" to true
            )
        }

        val stdin = "JSON.stringify(tclSolver(${Gson().toJson(request)}));"

        val stdout = try {
            synchronized(v8Lock) {
                initRuntime(cachedPreprocessed == null)
                val result = runV8(stdin)
                result
            }
        } catch (e: Exception) {
            Log.e(tag, "TCL n-function solve failed: ${e.message}")
            return nParams.associateWith { null }
        }

        val output: SolverOutput = try {
            Gson().fromJson(stdout, solverOutputType)
        } catch (e: JsonSyntaxException) {
            Log.e(tag, "Cannot parse TCL solver output: ${e.message}")
            return nParams.associateWith { null }
        }

        if (output.type == "error") {
            Log.e(tag, "TCL solver error: ${output.error}")
            if (cachedPreprocessed != null) {
                // cached preprocessed script may be stale (e.g. player build rotated)
                CacheService.clear(cacheSection)
                cachedPlayerCode = null
                shutdownIfNeeded()
            }
            return nParams.associateWith { null }
        }

        output.preprocessed_player?.let {
            CacheService.store(cacheSection, cacheKey, CachedData(it))
            cachedPlayerCode = it
        }

        val results = output.results ?: emptyMap()

        return nParams.associateWith { param -> results[challengeByParam[param]] }
    }

    private fun runV8(stdin: String): String {
        val runtime = v8Runtime ?: throw IllegalStateException("V8 runtime not initialized yet")
        return runtime.withLock {
            it.executeStringScript(stdin) ?: throw IllegalStateException("V8 runtime error: empty response")
        }
    }

    private fun initRuntime(needsParser: Boolean) {
        if (v8Runtime != null) {
            return
        }
        v8Runtime = V8.createV8Runtime()
        runV8(constructCommonStdin(needsParser))
    }

    private fun constructCommonStdin(needsParser: Boolean): String {
        // Cached challenges already contain executable code; AST tools are only for a new player.
        val libraries = if (needsParser) libFilenames else listOf(libFilenames.first(), libFilenames.last())
        val parserDefaults = if (needsParser) "" else "var meriyah = null, astring = null;"
        return """
        ${parserDefaults}
        ${loadScript(libraries, "Failed to read TCL challenge solver script")}
        "";
        """
    }

    private fun shutdownIfNeeded() {
        val runtime = v8Runtime ?: return
        runtime.withLock {
            it.release(false)
        }
        v8Runtime = null
    }
}

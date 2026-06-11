package ai.deepmost.triage.heads

import ai.deepmost.triage.config.HeadId

/** Result of running one head on one photo. */
sealed interface HeadResult {
    val headId: HeadId
    val engine: String

    data class Success(
        override val headId: HeadId,
        override val engine: String,
        val findings: List<AnalysisFinding>,
        val elapsedMs: Long
    ) : HeadResult

    /** A head that threw is recorded as FAILED with a reason, never killing the inspection. */
    data class Failed(
        override val headId: HeadId,
        override val engine: String,
        val reason: String
    ) : HeadResult
}

/**
 * Pluggable analysis head. Each implementation has a model path AND a complete classical-CV
 * fallback; [analyze] picks the engine (model if installed, else classical) and tags every
 * finding with the engine used.
 */
interface AnalysisHead {
    val id: HeadId

    /** Whether this head should run at the given station (per station config). */
    fun appliesTo(ctx: AnalysisContext): Boolean

    suspend fun analyze(ctx: AnalysisContext): List<AnalysisFinding>
}

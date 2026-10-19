package com.cashflow.app.debug.lab

/**
 * The pure state of "Run all checks" (or of one scenario): which scenarios are queued, which one and which of its steps is on screen.
 * "Looks right" goes to the next step, then to the next scenario; "Problem" marks the scenario as a problem and goes straight to the next
 * one. After the last scenario the run is over (the Results screen shows it).
 */
data class LabSession(val queue: List<String>, val index: Int = 0, val step: Int = 0, val failed: Boolean = false) {
    val scenarioId: String get() = queue[index]
    val total: Int get() = queue.size
}

/** What a mark did: the new session (null when the run is over) and, when a scenario was finished, its id and verdict. */
data class LabMarked(val session: LabSession?, val finishedId: String? = null, val verdict: Boolean? = null)

object LabFlow {
    fun start(ids: List<String>): LabSession? = if (ids.isEmpty()) null else LabSession(ids)

    /** [stepCount] is the number of steps of the scenario on screen. */
    fun mark(session: LabSession, ok: Boolean, stepCount: Int): LabMarked {
        val lastStep = session.step >= stepCount - 1
        if (ok && !lastStep) return LabMarked(session.copy(step = session.step + 1))
        val verdict = ok && !session.failed
        val nextIndex = session.index + 1
        val next = if (nextIndex < session.queue.size) LabSession(session.queue, nextIndex) else null
        return LabMarked(next, session.scenarioId, verdict)
    }
}

/** The verdicts of the Lab, one per scenario (true = looks right, false = problem; missing = not run). Stored only in the Lab's own storage. */
data class LabResults(val marks: Map<String, Boolean> = emptyMap()) {
    fun with(id: String, ok: Boolean) = LabResults(marks + (id to ok))

    fun mark(id: String): Boolean? = marks[id]

    fun rights(ids: List<String>) = ids.count { marks[it] == true }
    fun problems(ids: List<String>) = ids.count { marks[it] == false }
    fun notRun(ids: List<String>) = ids.count { marks[it] == null }

    /** "12 right, 1 problem, 4 not run". */
    fun summary(ids: List<String>) = "${rights(ids)} right, ${problems(ids)} ${if (problems(ids) == 1) "problem" else "problems"}, ${notRun(ids)} not run"

    fun encode(): String = marks.entries.joinToString(";") { "${it.key}=${if (it.value) 1 else 0}" }

    companion object {
        fun decode(text: String?): LabResults {
            if (text.isNullOrBlank()) return LabResults()
            return LabResults(
                text.split(';').mapNotNull { part ->
                    val bits = part.split('=')
                    if (bits.size == 2 && bits[0].isNotBlank() && (bits[1] == "1" || bits[1] == "0")) bits[0] to (bits[1] == "1") else null
                }.toMap(),
            )
        }
    }
}

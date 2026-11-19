package com.homebudget.core.sync

/** Version facts of the record currently stored locally. */
data class LocalVersion(val revision: Long, val operationId: String, val isDeleted: Boolean)

/** Version facts of an incoming remote operation. */
data class IncomingVersion(val revision: Long, val operationId: String, val baseOperationId: String?, val isDeleted: Boolean)

data class MergeDecision(val apply: Boolean, val conflict: Boolean)

/**
 * Deterministic, convergent merge policy (no wall-clock last-write-wins):
 *  1. Unknown record -> apply.
 *  2. Same operation, or a late-arriving ancestor of the local version -> ignore.
 *  3. Tombstones win: a deleted record is never resurrected; an edit that raced a delete is kept as a conflict.
 *  4. Causal successor (incoming.base == local.op) -> apply.
 *  5. Otherwise the edits are concurrent: the version with the greater (revision, operationId) wins on
 *     every device, and the losing version is preserved in the conflicts table for review.
 */
object MergePolicy {
    fun decide(local: LocalVersion?, incoming: IncomingVersion, incomingIsAncestorOfLocal: Boolean): MergeDecision {
        // revision 0 = local placeholder created from the Drive manifest during join.
        if (local == null || local.revision == 0L) return MergeDecision(apply = true, conflict = false)
        if (incoming.operationId == local.operationId || incomingIsAncestorOfLocal) return MergeDecision(false, false)
        if (local.isDeleted) {
            return MergeDecision(apply = false, conflict = !incoming.isDeleted)
        }
        if (incoming.isDeleted) {
            return MergeDecision(apply = true, conflict = incoming.baseOperationId != local.operationId)
        }
        if (incoming.baseOperationId == local.operationId) return MergeDecision(apply = true, conflict = false)
        val incomingWins = incoming.revision > local.revision ||
            (incoming.revision == local.revision && incoming.operationId > local.operationId)
        return MergeDecision(apply = incomingWins, conflict = true)
    }
}

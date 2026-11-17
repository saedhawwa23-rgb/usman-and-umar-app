package com.usmanandumar.workspace.core.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A record that a specific external action has already been performed.
 *
 * A workflow step can be re-attempted for three legitimate reasons: a bounded retry after a
 * transient failure, a resume after a crash, and a resume after a phone restart. In every case the
 * external side effect (a payment, a message, a file write) must happen at most once. The receipt is
 * the guard: it is written *before* the effect is requested and keyed by the step's stable
 * [WorkflowStep.receiptKey], so a later attempt for the same key observes the receipt and does not
 * run the tool again.
 */
@Serializable
data class ExecutionReceipt(
    val receiptKey: String,
    val runId: String,
    val stepId: String,
    val toolId: String,
    val recordedAtMs: Long,
    val success: Boolean,
    val output: Map<String, String> = emptyMap(),
)

/** Where receipts live. A durable store makes the guard survive a process death. */
interface ExecutionReceiptStore {
    fun find(receiptKey: String): ExecutionReceipt?
    fun record(receipt: ExecutionReceipt)
    fun all(): List<ExecutionReceipt>
    fun remove(receiptKey: String): Boolean
}

class InMemoryExecutionReceiptStore : ExecutionReceiptStore {
    private val receipts = LinkedHashMap<String, ExecutionReceipt>()

    @Synchronized
    override fun find(receiptKey: String): ExecutionReceipt? = receipts[receiptKey]

    @Synchronized
    override fun record(receipt: ExecutionReceipt) {
        receipts[receipt.receiptKey] = receipt
    }

    @Synchronized
    override fun all(): List<ExecutionReceipt> = receipts.values.toList()

    @Synchronized
    override fun remove(receiptKey: String): Boolean = receipts.remove(receiptKey) != null
}

/** One JSON file, written atomically via a temp file and a rename. */
class FileExecutionReceiptStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
) : ExecutionReceiptStore {

    private val cache = LinkedHashMap<String, ExecutionReceipt>()

    init {
        loadFromDisk().forEach { cache[it.receiptKey] = it }
    }

    @Synchronized
    override fun find(receiptKey: String): ExecutionReceipt? = cache[receiptKey]

    @Synchronized
    override fun record(receipt: ExecutionReceipt) {
        cache[receipt.receiptKey] = receipt
        flush()
    }

    @Synchronized
    override fun all(): List<ExecutionReceipt> = cache.values.toList()

    @Synchronized
    override fun remove(receiptKey: String): Boolean {
        val removed = cache.remove(receiptKey) != null
        if (removed) flush()
        return removed
    }

    private fun loadFromDisk(): List<ExecutionReceipt> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<ExecutionReceipt>>(text) }.getOrDefault(emptyList())
    }

    private fun flush() {
        file.parentFile?.mkdirs()
        val payload = json.encodeToString(cache.values.toList())
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            file.writeText(payload)
            temp.delete()
        }
    }
}

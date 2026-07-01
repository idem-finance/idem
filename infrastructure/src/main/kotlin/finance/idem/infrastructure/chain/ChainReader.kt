package finance.idem.infrastructure.chain

interface ChainReader {
    val chainKey: String

    fun poll(checkpoint: Long): List<DetectedTransfer>
}

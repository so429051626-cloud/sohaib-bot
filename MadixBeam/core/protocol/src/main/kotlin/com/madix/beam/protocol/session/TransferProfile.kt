package com.madix.beam.protocol.session

import com.madix.beam.protocol.Protocol
import kotlin.math.ceil

/** QR error-correction level. */
enum class ErrorCorrection { L, M, Q, H }

/**
 * Optical link profile: payload bytes per QR code, error correction, the number of codes shown
 * on screen simultaneously and the default display rate. The receiver needs no knowledge of the
 * profile; every code is self-describing.
 */
enum class TransferProfile(
    val id: String,
    val blockSize: Int,
    val errorCorrection: ErrorCorrection,
    val defaultFps: Int,
    val codesPerFrame: Int,
) {
    RELIABLE("reliable", 360, ErrorCorrection.M, 10, 1),
    BALANCED("balanced", 800, ErrorCorrection.L, 15, 1),
    FAST("fast", 900, ErrorCorrection.L, 15, 2),
    TURBO("turbo", 1100, ErrorCorrection.L, 20, 2);

    /** Nominal payload throughput in bytes per second at [fps]. */
    fun nominalBytesPerSecond(fps: Int = defaultFps): Double =
        blockSize.toDouble() * codesPerFrame * fps * (1.0 - 1.0 / Protocol.HEADER_INTERVAL)

    /**
     * Estimated transfer duration in seconds for a sealed payload of [sealedBytes], assuming
     * the receiving camera misses [lossRate] of the codes.
     */
    fun estimateSeconds(sealedBytes: Long, fps: Int = defaultFps, lossRate: Double = 0.3): Long {
        val blocks = ceil(sealedBytes.toDouble() / blockSize)
        val usefulCodes = blocks * 1.03 + 2
        val codes = usefulCodes / (1.0 - lossRate) / (1.0 - 1.0 / Protocol.HEADER_INTERVAL)
        val ticks = codes / codesPerFrame
        return ceil(ticks / fps.coerceAtLeast(1)).toLong().coerceAtLeast(1)
    }

    companion object {
        val DEFAULT = FAST

        fun fromId(id: String?): TransferProfile = entries.firstOrNull { it.id == id } ?: DEFAULT

        const val MIN_FPS = 3
        const val MAX_FPS = 30
    }
}

package com.madix.beam.protocol.session

import com.madix.beam.protocol.Protocol
import kotlin.math.ceil

/** QR error-correction level. */
enum class ErrorCorrection { L, M, Q, H }

/**
 * Optical link profile: payload bytes per QR frame, error correction and default frame rate.
 * Denser profiles move more data per second but require a steadier camera.
 */
enum class TransferProfile(
    val id: String,
    val blockSize: Int,
    val errorCorrection: ErrorCorrection,
    val defaultFps: Int,
) {
    RELIABLE("reliable", 320, ErrorCorrection.M, 8),
    BALANCED("balanced", 560, ErrorCorrection.L, 12),
    FAST("fast", 800, ErrorCorrection.L, 15),
    TURBO("turbo", 1100, ErrorCorrection.L, 20);

    /** Nominal payload throughput in bytes per second at [fps]. */
    fun nominalBytesPerSecond(fps: Int = defaultFps): Double =
        blockSize * fps * (1.0 - 1.0 / Protocol.HEADER_INTERVAL)

    /**
     * Estimated transfer duration in seconds for a sealed payload of [sealedBytes], assuming
     * the receiving camera misses [lossRate] of the frames.
     */
    fun estimateSeconds(sealedBytes: Long, fps: Int = defaultFps, lossRate: Double = 0.15): Long {
        val blocks = ceil(sealedBytes.toDouble() / blockSize)
        val usefulFrames = blocks * 1.03 + 2
        val frames = usefulFrames / (1.0 - lossRate) / (1.0 - 1.0 / Protocol.HEADER_INTERVAL)
        return ceil(frames / fps.coerceAtLeast(1)).toLong().coerceAtLeast(1)
    }

    companion object {
        val DEFAULT = BALANCED

        fun fromId(id: String?): TransferProfile = entries.firstOrNull { it.id == id } ?: DEFAULT

        const val MIN_FPS = 3
        const val MAX_FPS = 30
    }
}

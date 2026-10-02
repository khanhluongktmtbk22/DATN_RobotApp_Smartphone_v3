package com.example.datn_v1.tracking

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Binary LSTM fall classifier.
 *
 * Model: fall_lstm32v2.tflite
 * Input:  [1, 15, 69]
 * Output: [1, 2] = [probability of NO FALL, probability of FALL]
 *
 * The trained Fall probability threshold is 0.385. The app performs its own post-verification
 * after this raw classification.
 */
class LstmFallDetector(context: Context) {

    companion object {
        private const val TAG = "LstmFallDetector"
        private const val MODEL_NAME = "fall_lstm32v2.tflite"
        private const val SEQ_LEN = 15
        private const val NUM_FEATURES = FallDetectionQueue.NUM_FEATURES
        private const val MAX_MISSING_FRAMES = 60
        private const val FALL_THRESHOLD = 0.385f
    }

    data class FallResult(
        val probFall: Float,
        val probNormal: Float,
        val isReady: Boolean,
        val rawFallDetected: Boolean
    )

    private val interpreter: Interpreter
    private val queues = mutableMapOf<Int, FallDetectionQueue>()
    private val missingCnt = mutableMapOf<Int, Int>()
    private val lastResults = mutableMapOf<Int, FallResult>()

    init {
        val options = Interpreter.Options().apply { setNumThreads(2) }
        interpreter = Interpreter(loadModelFile(context, MODEL_NAME), options)

        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()
        require(inputShape.contentEquals(intArrayOf(1, SEQ_LEN, NUM_FEATURES))) {
            "Unexpected LSTM input shape: ${inputShape.contentToString()}"
        }
        require(outputShape.contentEquals(intArrayOf(1, 2))) {
            "Unexpected LSTM output shape: ${outputShape.contentToString()}"
        }
        Log.d(TAG, "LSTM input=${inputShape.contentToString()}, output=${outputShape.contentToString()}")
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fd = context.assets.openFd(modelName)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    fun processFeatures(trackId: Int, features: FloatArray): FallResult {
        missingCnt[trackId] = 0
        val queue = queues.getOrPut(trackId) { FallDetectionQueue(SEQ_LEN) }
        if (!queue.addFrame(features)) {
            return lastResults[trackId] ?: noFallResult(isReady = false)
        }

        val inputBuffer = ByteBuffer.allocateDirect(SEQ_LEN * NUM_FEATURES * 4).apply {
            order(ByteOrder.nativeOrder())
            queue.getFlattenedInput().forEach { putFloat(it) }
            rewind()
        }
        val output = Array(1) { FloatArray(2) }

        try {
            interpreter.run(inputBuffer, output)
        } catch (error: Exception) {
            Log.e(TAG, "LSTM inference error for track $trackId", error)
            return lastResults[trackId] ?: noFallResult(isReady = true)
        }

        val probNormal = output[0][0]
        val probFall = output[0][1]
        // Use the calibrated Fall threshold, not the softmax argmax threshold of 0.5.
        val result = FallResult(
            probFall = probFall,
            probNormal = probNormal,
            isReady = true,
            rawFallDetected = probFall >= FALL_THRESHOLD
        )
        lastResults[trackId] = result

        Log.d(
            TAG,
            "Track $trackId: noFall=%.3f fall=%.3f -> %s".format(
                probNormal,
                probFall,
                if (result.rawFallDetected) "FALL" else "NO FALL"
            )
        )
        return result
    }

    fun cleanupStaleQueues(activeTrackIds: Set<Int>) {
        val toRemove = mutableListOf<Int>()
        for (trackId in queues.keys) {
            if (trackId !in activeTrackIds) {
                val count = (missingCnt[trackId] ?: 0) + 1
                missingCnt[trackId] = count
                if (count > MAX_MISSING_FRAMES) toRemove += trackId
            }
        }
        for (trackId in toRemove) {
            queues.remove(trackId)
            missingCnt.remove(trackId)
            lastResults.remove(trackId)
            Log.d(TAG, "Cleaned stale queue for track $trackId")
        }
    }

    /** Keep the sequence with the same person when SORT promotes a track to the primary ID. */
    fun transferStateTo(oldId: Int, newId: Int) {
        if (oldId == newId) return
        queues.remove(oldId)?.let { if (newId !in queues) queues[newId] = it }
        lastResults.remove(oldId)?.let { if (newId !in lastResults) lastResults[newId] = it }
        missingCnt.remove(oldId)
        if (newId in queues) missingCnt[newId] = 0
        Log.i(TAG, "LSTM sequence transferred: track $oldId -> $newId")
    }

    fun reset() {
        queues.clear()
        missingCnt.clear()
        lastResults.clear()
    }

    fun close() {
        reset()
        interpreter.close()
    }

    private fun noFallResult(isReady: Boolean) = FallResult(
        probFall = 0f,
        probNormal = 1f,
        isReady = isReady,
        rawFallDetected = false
    )
}

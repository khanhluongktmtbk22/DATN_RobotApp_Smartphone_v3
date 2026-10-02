package com.example.datn_v1.tracking

/** Geometry bổ sung cho trường hợp người nằm dọc theo trục nhìn của camera. */
internal object PostureGeometry {
    private const val FLOOR_MIN_BBOX_ASPECT = 0.68f
    private const val FLOOR_MAX_POSE_HEIGHT = 0.55f
    private const val FLOOR_MAX_TORSO_DY = 0.20f

    /**
     * Khi người nằm hướng chân về camera, bbox/keypoint vẫn có thể trông thẳng đứng.
     * Tư thế này có bbox tương đối vuông nhưng chiều cao pose và vai-hông bị co ngắn
     * mạnh do phối cảnh. Với điều khiển robot, ưu tiên nhận nhầm an toàn (không tiến).
     */
    fun isForeshortenedFloorPose(
        hasLowerBody: Boolean,
        bboxAspect: Float,
        poseHeight: Float?,
        torsoDy: Float?
    ): Boolean =
        hasLowerBody &&
            bboxAspect >= FLOOR_MIN_BBOX_ASPECT &&
            poseHeight != null && poseHeight <= FLOOR_MAX_POSE_HEIGHT &&
            torsoDy != null && torsoDy <= FLOOR_MAX_TORSO_DY
}

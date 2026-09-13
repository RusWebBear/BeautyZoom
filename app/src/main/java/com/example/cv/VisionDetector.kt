package com.example.cv

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.model.DetectedPerson
import com.example.model.FaceLandmarks
import com.example.model.Point2D
import com.example.model.PoseLandmarks
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark as MLPoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VisionDetector {

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
        FaceDetection.getClient(options)
    }

    private val poseDetector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    private val selfieSegmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    suspend fun detectPeopleAndFeatures(
        bitmap: Bitmap
    ): Pair<List<DetectedPerson>, SegmentationMask?> = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // Run ML Kit Face Detection, Pose Detection, and Segmentation in parallel/sequence
        val faceTask = faceDetector.process(inputImage)
        val poseTask = poseDetector.process(inputImage)
        val segTask = selfieSegmenter.process(inputImage)

        val faces: List<Face> = try {
            Tasks.await(faceTask)
        } catch (e: Exception) {
            emptyList()
        }

        val pose: Pose? = try {
            Tasks.await(poseTask)
        } catch (e: Exception) {
            null
        }

        val segmentationMask: SegmentationMask? = try {
            Tasks.await(segTask)
        } catch (e: Exception) {
            null
        }

        val detectedPersons = mutableListOf<DetectedPerson>()

        // Map faces to DetectedPerson
        faces.forEachIndexed { index, face ->
            val faceLandmarks = extractFaceLandmarks(face)
            val faceBounds = RectF(face.boundingBox)

            // Estimate person full bounds based on face if pose is available or expanded face bounds
            val estimatedPersonBounds = RectF(
                (faceBounds.left - faceBounds.width() * 0.5f).coerceAtLeast(0f),
                (faceBounds.top - faceBounds.height() * 0.3f).coerceAtLeast(0f),
                (faceBounds.right + faceBounds.width() * 0.5f).coerceAtMost(bitmap.width.toFloat()),
                (faceBounds.bottom + faceBounds.height() * 3.5f).coerceAtMost(bitmap.height.toFloat())
            )

            // Associate pose if available and overlapping
            val poseLandmarks = if (pose != null && isPoseAssociatedWithFace(pose, faceBounds)) {
                extractPoseLandmarks(pose, bitmap.width, bitmap.height)
            } else if (index == 0 && pose != null) {
                // If single pose and 1 face, associate
                extractPoseLandmarks(pose, bitmap.width, bitmap.height)
            } else {
                // Generate synthetic body bounds based on face position
                createEstimatedPose(faceBounds, bitmap.width.toFloat(), bitmap.height.toFloat())
            }

            detectedPersons.add(
                DetectedPerson(
                    id = index + 1,
                    bounds = estimatedPersonBounds,
                    face = faceLandmarks,
                    pose = poseLandmarks,
                    label = "Person #${index + 1}"
                )
            )
        }

        // If no face was detected but pose was detected, create a person from pose
        if (detectedPersons.isEmpty() && pose != null) {
            val poseLandmarks = extractPoseLandmarks(pose, bitmap.width, bitmap.height)
            if (poseLandmarks != null) {
                detectedPersons.add(
                    DetectedPerson(
                        id = 1,
                        bounds = poseLandmarks.bounds,
                        face = null,
                        pose = poseLandmarks,
                        label = "Person #1"
                    )
                )
            }
        }

        // If neither face nor pose detected (e.g. abstract art or back of head), fallback to frame person
        if (detectedPersons.isEmpty()) {
            val defaultBounds = RectF(
                bitmap.width * 0.2f,
                bitmap.height * 0.1f,
                bitmap.width * 0.8f,
                bitmap.height * 0.9f
            )
            detectedPersons.add(
                DetectedPerson(
                    id = 1,
                    bounds = defaultBounds,
                    face = createFallbackFace(bitmap.width, bitmap.height),
                    pose = createFallbackPose(bitmap.width, bitmap.height),
                    label = "Subject #1"
                )
            )
        }

        Pair(detectedPersons, segmentationMask)
    }

    private fun extractFaceLandmarks(face: Face): FaceLandmarks {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let { Point2D(it.x, it.y) }
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { Point2D(it.x, it.y) }
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position?.let { Point2D(it.x, it.y) }
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.let { Point2D(it.x, it.y) }
        val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position?.let { Point2D(it.x, it.y) }
        val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position?.let { Point2D(it.x, it.y) }

        val upperLip = face.getContour(FaceContour.UPPER_LIP_TOP)?.points?.map { Point2D(it.x, it.y) } ?: emptyList()
        val lowerLip = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points?.map { Point2D(it.x, it.y) } ?: emptyList()
        val faceOval = face.getContour(FaceContour.FACE)?.points?.map { Point2D(it.x, it.y) } ?: emptyList()

        val jawline = if (faceOval.size >= 18) {
            faceOval.subList(faceOval.size / 4, (faceOval.size * 3) / 4)
        } else {
            faceOval
        }

        return FaceLandmarks(
            bounds = RectF(face.boundingBox),
            leftEyeCenter = leftEye,
            rightEyeCenter = rightEye,
            noseTip = nose,
            mouthCenter = mouthBottom,
            upperLipContours = upperLip,
            lowerLipContours = lowerLip,
            jawlineContours = jawline,
            leftCheek = leftCheek,
            rightCheek = rightCheek,
            faceOval = faceOval
        )
    }

    private fun isPoseAssociatedWithFace(pose: Pose, faceBounds: RectF): Boolean {
        val nose = pose.getPoseLandmark(MLPoseLandmark.NOSE)?.position
        return if (nose != null) {
            faceBounds.contains(nose.x, nose.y)
        } else {
            val leftEye = pose.getPoseLandmark(MLPoseLandmark.LEFT_EYE)?.position
            leftEye != null && faceBounds.contains(leftEye.x, leftEye.y)
        }
    }

    private fun extractPoseLandmarks(pose: Pose, imageWidth: Int, imageHeight: Int): PoseLandmarks? {
        val nose = pose.getPoseLandmark(MLPoseLandmark.NOSE)?.position?.let { Point2D(it.x, it.y) }
        val leftShoulder = pose.getPoseLandmark(MLPoseLandmark.LEFT_SHOULDER)?.position?.let { Point2D(it.x, it.y) }
        val rightShoulder = pose.getPoseLandmark(MLPoseLandmark.RIGHT_SHOULDER)?.position?.let { Point2D(it.x, it.y) }
        val leftElbow = pose.getPoseLandmark(MLPoseLandmark.LEFT_ELBOW)?.position?.let { Point2D(it.x, it.y) }
        val rightElbow = pose.getPoseLandmark(MLPoseLandmark.RIGHT_ELBOW)?.position?.let { Point2D(it.x, it.y) }
        val leftWrist = pose.getPoseLandmark(MLPoseLandmark.LEFT_WRIST)?.position?.let { Point2D(it.x, it.y) }
        val rightWrist = pose.getPoseLandmark(MLPoseLandmark.RIGHT_WRIST)?.position?.let { Point2D(it.x, it.y) }
        val leftHip = pose.getPoseLandmark(MLPoseLandmark.LEFT_HIP)?.position?.let { Point2D(it.x, it.y) }
        val rightHip = pose.getPoseLandmark(MLPoseLandmark.RIGHT_HIP)?.position?.let { Point2D(it.x, it.y) }
        val leftKnee = pose.getPoseLandmark(MLPoseLandmark.LEFT_KNEE)?.position?.let { Point2D(it.x, it.y) }
        val rightKnee = pose.getPoseLandmark(MLPoseLandmark.RIGHT_KNEE)?.position?.let { Point2D(it.x, it.y) }
        val leftAnkle = pose.getPoseLandmark(MLPoseLandmark.LEFT_ANKLE)?.position?.let { Point2D(it.x, it.y) }
        val rightAnkle = pose.getPoseLandmark(MLPoseLandmark.RIGHT_ANKLE)?.position?.let { Point2D(it.x, it.y) }

        val waistCenter = if (leftHip != null && rightHip != null) {
            Point2D((leftHip.x + rightHip.x) / 2f, (leftHip.y + rightHip.y) / 2f)
        } else null

        val spineCenter = if (leftShoulder != null && rightShoulder != null && waistCenter != null) {
            val shoulderCenterY = (leftShoulder.y + rightShoulder.y) / 2f
            val shoulderCenterX = (leftShoulder.x + rightShoulder.x) / 2f
            Point2D((shoulderCenterX + waistCenter.x) / 2f, (shoulderCenterY + waistCenter.y) / 2f)
        } else null

        // Calculate bounding box containing all valid points
        val validPoints = listOfNotNull(
            nose, leftShoulder, rightShoulder, leftElbow, rightElbow,
            leftWrist, rightWrist, leftHip, rightHip, leftKnee, rightKnee,
            leftAnkle, rightAnkle
        )

        if (validPoints.isEmpty()) return null

        val minX = validPoints.minOf { it.x }.coerceAtLeast(0f)
        val maxX = validPoints.maxOf { it.x }.coerceAtMost(imageWidth.toFloat())
        val minY = validPoints.minOf { it.y }.coerceAtLeast(0f)
        val maxY = validPoints.maxOf { it.y }.coerceAtMost(imageHeight.toFloat())

        return PoseLandmarks(
            bounds = RectF(minX, minY, maxX, maxY),
            nose = nose,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            leftElbow = leftElbow,
            rightElbow = rightElbow,
            leftWrist = leftWrist,
            rightWrist = rightWrist,
            leftHip = leftHip,
            rightHip = rightHip,
            leftKnee = leftKnee,
            rightKnee = rightKnee,
            leftAnkle = leftAnkle,
            rightAnkle = rightAnkle,
            waistCenter = waistCenter,
            spineCenter = spineCenter
        )
    }

    private fun createEstimatedPose(faceBounds: RectF, imgW: Float, imgH: Float): PoseLandmarks {
        val centerX = faceBounds.centerX()
        val faceH = faceBounds.height()
        val faceW = faceBounds.width()

        val shoulderY = faceBounds.bottom + faceH * 0.25f
        val waistY = faceBounds.bottom + faceH * 1.5f
        val hipY = faceBounds.bottom + faceH * 2.0f
        val kneeY = (faceBounds.bottom + faceH * 3.3f).coerceAtMost(imgH * 0.85f)
        val ankleY = (faceBounds.bottom + faceH * 4.4f).coerceAtMost(imgH * 0.98f)

        val shoulderSpan = faceW * 1.6f
        val hipSpan = faceW * 1.2f

        return PoseLandmarks(
            bounds = RectF(
                (centerX - shoulderSpan).coerceAtLeast(0f),
                faceBounds.top,
                (centerX + shoulderSpan).coerceAtMost(imgW),
                ankleY
            ),
            nose = Point2D(centerX, faceBounds.centerY()),
            leftShoulder = Point2D(centerX - shoulderSpan / 2, shoulderY),
            rightShoulder = Point2D(centerX + shoulderSpan / 2, shoulderY),
            leftElbow = Point2D(centerX - shoulderSpan * 0.7f, waistY),
            rightElbow = Point2D(centerX + shoulderSpan * 0.7f, waistY),
            leftWrist = Point2D(centerX - shoulderSpan * 0.8f, hipY),
            rightWrist = Point2D(centerX + shoulderSpan * 0.8f, hipY),
            leftHip = Point2D(centerX - hipSpan / 2, hipY),
            rightHip = Point2D(centerX + hipSpan / 2, hipY),
            leftKnee = Point2D(centerX - hipSpan * 0.45f, kneeY),
            rightKnee = Point2D(centerX + hipSpan * 0.45f, kneeY),
            leftAnkle = Point2D(centerX - hipSpan * 0.4f, ankleY),
            rightAnkle = Point2D(centerX + hipSpan * 0.4f, ankleY),
            waistCenter = Point2D(centerX, waistY),
            spineCenter = Point2D(centerX, (shoulderY + waistY) / 2)
        )
    }

    private fun createFallbackFace(width: Int, height: Int): FaceLandmarks {
        val cx = width * 0.5f
        val cy = height * 0.3f
        val size = width * 0.22f

        return FaceLandmarks(
            bounds = RectF(cx - size, cy - size * 1.2f, cx + size, cy + size * 1.2f),
            leftEyeCenter = Point2D(cx - size * 0.4f, cy - size * 0.2f),
            rightEyeCenter = Point2D(cx + size * 0.4f, cy - size * 0.2f),
            noseTip = Point2D(cx, cy + size * 0.1f),
            mouthCenter = Point2D(cx, cy + size * 0.5f),
            leftCheek = Point2D(cx - size * 0.6f, cy + size * 0.2f),
            rightCheek = Point2D(cx + size * 0.6f, cy + size * 0.2f)
        )
    }

    private fun createFallbackPose(width: Int, height: Int): PoseLandmarks {
        val cx = width * 0.5f
        val top = height * 0.35f

        return PoseLandmarks(
            bounds = RectF(width * 0.2f, height * 0.2f, width * 0.8f, height * 0.95f),
            nose = Point2D(cx, height * 0.25f),
            leftShoulder = Point2D(cx - width * 0.2f, top + height * 0.05f),
            rightShoulder = Point2D(cx + width * 0.2f, top + height * 0.05f),
            leftElbow = Point2D(cx - width * 0.25f, top + height * 0.18f),
            rightElbow = Point2D(cx + width * 0.25f, top + height * 0.18f),
            leftWrist = Point2D(cx - width * 0.28f, top + height * 0.32f),
            rightWrist = Point2D(cx + width * 0.28f, top + height * 0.32f),
            leftHip = Point2D(cx - width * 0.15f, top + height * 0.35f),
            rightHip = Point2D(cx + width * 0.15f, top + height * 0.35f),
            leftKnee = Point2D(cx - width * 0.14f, top + height * 0.55f),
            rightKnee = Point2D(cx + width * 0.14f, top + height * 0.55f),
            leftAnkle = Point2D(cx - width * 0.12f, top + height * 0.75f),
            rightAnkle = Point2D(cx + width * 0.12f, top + height * 0.75f),
            waistCenter = Point2D(cx, top + height * 0.25f),
            spineCenter = Point2D(cx, top + height * 0.15f)
        )
    }
}

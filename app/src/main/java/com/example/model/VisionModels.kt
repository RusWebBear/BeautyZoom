package com.example.model

import android.graphics.Bitmap
import android.graphics.RectF

data class Point2D(val x: Float, val y: Float) {
    fun distanceTo(other: Point2D): Float {
        val dx = x - other.x
        val dy = y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

data class FaceLandmarks(
    val bounds: RectF,
    val leftEyeCenter: Point2D?,
    val rightEyeCenter: Point2D?,
    val noseTip: Point2D?,
    val mouthCenter: Point2D?,
    val upperLipContours: List<Point2D> = emptyList(),
    val lowerLipContours: List<Point2D> = emptyList(),
    val jawlineContours: List<Point2D> = emptyList(),
    val leftCheek: Point2D?,
    val rightCheek: Point2D?,
    val faceOval: List<Point2D> = emptyList(),
    val forehead: Point2D? = null
)

data class PoseLandmarks(
    val bounds: RectF,
    val nose: Point2D?,
    val leftShoulder: Point2D?,
    val rightShoulder: Point2D?,
    val leftElbow: Point2D?,
    val rightElbow: Point2D?,
    val leftWrist: Point2D?,
    val rightWrist: Point2D?,
    val leftHip: Point2D?,
    val rightHip: Point2D?,
    val leftKnee: Point2D?,
    val rightKnee: Point2D?,
    val leftAnkle: Point2D?,
    val rightAnkle: Point2D?,
    val waistCenter: Point2D? = null,
    val spineCenter: Point2D? = null
)

data class DetectedPerson(
    val id: Int,
    val bounds: RectF,
    val face: FaceLandmarks?,
    val pose: PoseLandmarks?,
    val hasFace: Boolean = face != null,
    val hasBody: Boolean = pose != null,
    val label: String = "Человек #$id"
)

enum class ColorGradePreset(val displayName: String) {
    ORIGINAL("Оригинал"),
    NATURAL_GLOW("Сияние"),
    WARM_SUNSET("Теплый"),
    COOL_STUDIO("Студия"),
    CINEMATIC("Кино"),
    VINTAGE("Винтаж"),
    NOIR("Ч/Б Нуар")
}

enum class SkinTextureMode(val displayName: String) {
    BLEMISH_REMOVAL("Удаление дефектов"),
    PORE_DETAILS("Добавление пор")
}

data class PersonEditParams(
    // Facial Retouching
    val skinToneShift: Float = 0f,       // -100 to +100
    val skinToneWarmth: Float = 0f,      // -100 to +100
    val skinSmoothing: Float = 0f,       // 0 to 100
    val skinDefectRemoval: Float = 0f,   // 0 to 100
    val skinTextureMode: SkinTextureMode = SkinTextureMode.BLEMISH_REMOVAL,
    val skinTextureIntensity: Float = 0f, // 0 to 100: Pore details or blemish removal
    val eyesSize: Float = 0f,            // -100 to +100
    val lipsVolume: Float = 0f,          // -100 to +100
    val lipsShape: Float = 0f,           // -100 to +100
    val cheekbones: Float = 0f,          // -100 to +100
    val chinReshaping: Float = 0f,       // -100 to +100
    val hairColorArgb: Int? = null,      // Selected color for hair
    val hairColorIntensity: Float = 0f,  // 0 to 100

    // Body Morphing
    val waistResizing: Float = 0f,       // -100 to +100 (narrow/widen)
    val hipsResizing: Float = 0f,        // -100 to +100
    val glutesResizing: Float = 0f,      // -100 to +100
    val anklesResizing: Float = 0f,      // -100 to +100
    val heightAdjustment: Float = 0f     // -100 to +100
) {
    val isDefault: Boolean
        get() = skinToneShift == 0f && skinToneWarmth == 0f && skinSmoothing == 0f &&
                skinDefectRemoval == 0f && skinTextureIntensity == 0f && eyesSize == 0f && lipsVolume == 0f &&
                lipsShape == 0f && cheekbones == 0f && chinReshaping == 0f &&
                hairColorIntensity == 0f && waistResizing == 0f && hipsResizing == 0f &&
                glutesResizing == 0f && anklesResizing == 0f && heightAdjustment == 0f
}

data class GlobalEditParams(
    val exposure: Float = 0f,        // -100 to +100
    val contrast: Float = 0f,        // -100 to +100
    val saturation: Float = 0f,      // -100 to +100
    val dynamicRange: Float = 0f,    // 0 to 100
    val noiseReduction: Float = 0f,  // 0 to 100
    val backgroundBlur: Float = 0f,  // 0 to 100 (Portrait Bokeh)
    val backgroundBrightness: Float = 0f, // -100 to +100 (Background Exposure)
    val colorGrade: ColorGradePreset = ColorGradePreset.ORIGINAL
) {
    val isDefault: Boolean
        get() = exposure == 0f && contrast == 0f && saturation == 0f &&
                dynamicRange == 0f && noiseReduction == 0f &&
                backgroundBlur == 0f && backgroundBrightness == 0f &&
                colorGrade == ColorGradePreset.ORIGINAL
}

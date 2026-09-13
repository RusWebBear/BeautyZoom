package com.example.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.example.model.ColorGradePreset
import com.example.model.DetectedPerson
import com.example.model.GlobalEditParams
import com.example.model.PersonEditParams
import com.example.model.Point2D
import com.example.model.SkinTextureMode
import com.google.mlkit.vision.segmentation.SegmentationMask
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MorphWarpEngine {

    companion object {
        private const val MESH_COLS = 32
        private const val MESH_ROWS = 32
    }

    suspend fun applyEdits(
        originalBitmap: Bitmap,
        people: List<DetectedPerson>,
        personParamsMap: Map<Int, PersonEditParams>,
        globalParams: GlobalEditParams,
        segmentationMask: SegmentationMask? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = originalBitmap.width
        val height = originalBitmap.height

        // Step 1: Check if any person has geometric mesh warping params
        val hasGeometricEdits = personParamsMap.values.any { params ->
            params.eyesSize != 0f || params.lipsVolume != 0f || params.lipsShape != 0f ||
                    params.cheekbones != 0f || params.chinReshaping != 0f ||
                    params.waistResizing != 0f || params.hipsResizing != 0f ||
                    params.glutesResizing != 0f || params.anklesResizing != 0f ||
                    params.heightAdjustment != 0f
        }

        var currentBitmap = originalBitmap

        // Step 2: Apply Geometric Mesh Warping if needed
        if (hasGeometricEdits) {
            currentBitmap = applyMeshDeformations(currentBitmap, people, personParamsMap)
        }

        // Step 3: Apply Per-Person Facial Pixel Filters (Skin tone, Skin smoothing with Frequency Separation, Hair recolor, Advanced Skin Texture)
        val hasFacialPixelEdits = personParamsMap.values.any { params ->
            params.skinSmoothing > 0f || params.skinDefectRemoval > 0f ||
                    params.skinTextureIntensity > 0f ||
                    params.skinToneShift != 0f || params.skinToneWarmth != 0f ||
                    (params.hairColorArgb != null && params.hairColorIntensity > 0f)
        }

        if (hasFacialPixelEdits) {
            currentBitmap = applyFacialPixelEdits(currentBitmap, people, personParamsMap)
        }

        // Step 4: Apply Portrait Background Effects (Bokeh Blur & Brightness/Vignette)
        if (globalParams.backgroundBlur > 0f || globalParams.backgroundBrightness != 0f) {
            currentBitmap = applyBackgroundPortraitEffects(
                source = currentBitmap,
                segmentationMask = segmentationMask,
                people = people,
                blurStrength = globalParams.backgroundBlur,
                brightnessShift = globalParams.backgroundBrightness
            )
        }

        // Step 5: Apply Global Enhancements (Exposure, Contrast, Saturation, Dynamic Range, Color Grading Presets)
        if (!globalParams.isDefault) {
            currentBitmap = applyGlobalEnhancements(currentBitmap, globalParams)
        }

        currentBitmap
    }

    // --- Mesh Deformation Engine using Canvas.drawBitmapMesh ---

    private fun applyMeshDeformations(
        source: Bitmap,
        people: List<DetectedPerson>,
        personParamsMap: Map<Int, PersonEditParams>
    ): Bitmap {
        val width = source.width.toFloat()
        val height = source.height.toFloat()

        val numVertices = (MESH_COLS + 1) * (MESH_ROWS + 1)
        val origVerts = FloatArray(numVertices * 2)
        val deformedVerts = FloatArray(numVertices * 2)

        // Initialize regular grid
        var index = 0
        for (r in 0..MESH_ROWS) {
            val fy = r.toFloat() / MESH_ROWS * height
            for (c in 0..MESH_COLS) {
                val fx = c.toFloat() / MESH_COLS * width
                origVerts[index * 2] = fx
                origVerts[index * 2 + 1] = fy
                deformedVerts[index * 2] = fx
                deformedVerts[index * 2 + 1] = fy
                index++
            }
        }

        // Accumulate warp vectors per vertex across all persons
        for (person in people) {
            val params = personParamsMap[person.id] ?: continue
            if (params.isDefault) continue

            applyPersonWarpToGrid(person, params, origVerts, deformedVerts, width, height)
        }

        // Render deformed bitmap using hardware mesh
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawBitmapMesh(
            source,
            MESH_COLS,
            MESH_ROWS,
            deformedVerts,
            0,
            null,
            0,
            paint
        )

        return result
    }

    private fun applyPersonWarpToGrid(
        person: DetectedPerson,
        params: PersonEditParams,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        imgW: Float,
        imgH: Float
    ) {
        val numVertices = (MESH_COLS + 1) * (MESH_ROWS + 1)

        // 1. Eyes Resizing (Symmetric localized radial warp around left and right eye centers)
        if (params.eyesSize != 0f && person.face != null) {
            val strength = (params.eyesSize / 100f) * 0.35f
            val faceW = person.face.bounds.width()
            val radius = faceW * 0.18f

            person.face.leftEyeCenter?.let { center ->
                displaceRadialMagnify(center, radius, strength, origVerts, deformedVerts, numVertices)
            }
            person.face.rightEyeCenter?.let { center ->
                displaceRadialMagnify(center, radius, strength, origVerts, deformedVerts, numVertices)
            }
        }

        // 2. Lips Volume & Shape (Vertical expansion & Cupid's bow contour lift)
        if ((params.lipsVolume != 0f || params.lipsShape != 0f) && person.face != null) {
            val mouthCenter = person.face.mouthCenter ?: person.face.noseTip?.let {
                Point2D(it.x, it.y + person.face.bounds.height() * 0.3f)
            }
            if (mouthCenter != null) {
                val lipsRadius = person.face.bounds.width() * 0.22f
                val volumeStrength = (params.lipsVolume / 100f) * 0.25f
                val shapeStrength = (params.lipsShape / 100f) * 0.20f

                displaceLipsWarp(mouthCenter, lipsRadius, volumeStrength, shapeStrength, origVerts, deformedVerts, numVertices)
            }
        }

        // 3. Cheekbones (Horizontal & diagonal displacement along zygomatic arch)
        if (params.cheekbones != 0f && person.face != null) {
            val cheekStrength = (params.cheekbones / 100f) * 0.18f
            val faceW = person.face.bounds.width()
            val cheekRadius = faceW * 0.22f

            val leftCheek = person.face.leftCheek ?: Point2D(person.face.bounds.left + faceW * 0.2f, person.face.bounds.centerY())
            val rightCheek = person.face.rightCheek ?: Point2D(person.face.bounds.right - faceW * 0.2f, person.face.bounds.centerY())

            // Displace left cheek outward/inward and slightly upward
            displaceDirected(leftCheek, cheekRadius, -cheekStrength * faceW * 0.3f, -cheekStrength * faceW * 0.15f, origVerts, deformedVerts, numVertices)
            // Displace right cheek outward/inward and slightly upward
            displaceDirected(rightCheek, cheekRadius, cheekStrength * faceW * 0.3f, -cheekStrength * faceW * 0.15f, origVerts, deformedVerts, numVertices)
        }

        // 4. Chin Reshaping (Narrowing / widening & lengthening / shortening jawline)
        if (params.chinReshaping != 0f && person.face != null) {
            val chinStrength = (params.chinReshaping / 100f) * 0.25f
            val faceW = person.face.bounds.width()
            val chinCenter = Point2D(person.face.bounds.centerX(), person.face.bounds.bottom)
            val chinRadius = faceW * 0.35f

            // Negative narrows & shortens chin, positive widens & lengthens
            displaceChin(chinCenter, chinRadius, chinStrength, faceW, origVerts, deformedVerts, numVertices)
        }

        // 5. Waist Resizing (Bilateral inward / outward warp centered between ribs and hips)
        if (params.waistResizing != 0f && person.pose != null) {
            val waistStrength = (params.waistResizing / 100f) * 0.30f
            val waistCenter = person.pose.waistCenter ?: person.pose.spineCenter ?: Point2D(person.pose.bounds.centerX(), person.pose.bounds.centerY())
            val bodyW = person.pose.bounds.width()
            val waistRadius = max(bodyW * 0.45f, imgW * 0.15f)

            displaceWaist(waistCenter, waistRadius, waistStrength, bodyW, origVerts, deformedVerts, numVertices)
        }

        // 6. Hips Resizing (Curvature deformation between hip joints and upper thighs)
        if (params.hipsResizing != 0f && person.pose != null) {
            val hipStrength = (params.hipsResizing / 100f) * 0.30f
            val leftHip = person.pose.leftHip
            val rightHip = person.pose.rightHip
            val bodyW = person.pose.bounds.width()
            val hipRadius = max(bodyW * 0.35f, imgW * 0.12f)

            if (leftHip != null && rightHip != null) {
                displaceDirected(leftHip, hipRadius, -hipStrength * bodyW * 0.35f, 0f, origVerts, deformedVerts, numVertices)
                displaceDirected(rightHip, hipRadius, hipStrength * bodyW * 0.35f, 0f, origVerts, deformedVerts, numVertices)
            } else {
                val center = person.pose.waistCenter ?: Point2D(person.pose.bounds.centerX(), person.pose.bounds.bottom * 0.6f)
                displaceWaist(center, hipRadius * 1.3f, hipStrength, bodyW, origVerts, deformedVerts, numVertices)
            }
        }

        // 7. Glutes Resizing (Localized radial expansion/contraction at pelvic level)
        if (params.glutesResizing != 0f && person.pose != null) {
            val gluteStrength = (params.glutesResizing / 100f) * 0.30f
            val pelvicCenter = person.pose.waistCenter?.let { Point2D(it.x, it.y + person.pose.bounds.height() * 0.15f) }
                ?: Point2D(person.pose.bounds.centerX(), person.pose.bounds.bottom * 0.55f)
            val bodyW = person.pose.bounds.width()
            val gluteRadius = max(bodyW * 0.38f, imgW * 0.14f)

            displaceRadialMagnify(pelvicCenter, gluteRadius, gluteStrength, origVerts, deformedVerts, numVertices)
        }

        // 8. Ankles Resizing (Narrowing/widening cylindrical warp at lower calves/malleolus)
        if (params.anklesResizing != 0f && person.pose != null) {
            val ankleStrength = (params.anklesResizing / 100f) * 0.25f
            val leftAnkle = person.pose.leftAnkle
            val rightAnkle = person.pose.rightAnkle
            val bodyW = person.pose.bounds.width()
            val ankleRadius = max(bodyW * 0.20f, imgW * 0.08f)

            if (leftAnkle != null) {
                displaceCylindricalAnkle(leftAnkle, ankleRadius, ankleStrength, origVerts, deformedVerts, numVertices)
            }
            if (rightAnkle != null) {
                displaceCylindricalAnkle(rightAnkle, ankleRadius, ankleStrength, origVerts, deformedVerts, numVertices)
            }
        }

        // 9. Height Adjustment (Proportional vertical stretching along spine and leg bones)
        if (params.heightAdjustment != 0f && person.pose != null) {
            val heightStrength = (params.heightAdjustment / 100f) * 0.12f
            val topY = person.face?.bounds?.top ?: person.pose.bounds.top
            val bottomY = person.pose.bounds.bottom
            val spineX = person.pose.spineCenter?.x ?: person.pose.bounds.centerX()

            displaceHeightStretch(topY, bottomY, spineX, heightStrength, origVerts, deformedVerts, numVertices, imgH)
        }
    }

    // --- Localized Displacement Mathematical Kernels ---

    private fun displaceRadialMagnify(
        center: Point2D,
        radius: Float,
        strength: Float,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        count: Int
    ) {
        val r2 = radius * radius
        for (i in 0 until count) {
            val ox = origVerts[i * 2]
            val oy = origVerts[i * 2 + 1]
            val dx = ox - center.x
            val dy = oy - center.y
            val dist2 = dx * dx + dy * dy
            if (dist2 < r2) {
                val factor = 1f - dist2 / r2
                val displacement = factor * factor * strength
                // Magnify pushes outward (source moves inward relative to center)
                deformedVerts[i * 2] += dx * displacement
                deformedVerts[i * 2 + 1] += dy * displacement
            }
        }
    }

    private fun displaceDirected(
        center: Point2D,
        radius: Float,
        dispX: Float,
        dispY: Float,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        count: Int
    ) {
        val r2 = radius * radius
        for (i in 0 until count) {
            val ox = origVerts[i * 2]
            val oy = origVerts[i * 2 + 1]
            val dx = ox - center.x
            val dy = oy - center.y
            val dist2 = dx * dx + dy * dy
            if (dist2 < r2) {
                val factor = 1f - dist2 / r2
                val weight = factor * factor
                deformedVerts[i * 2] += dispX * weight
                deformedVerts[i * 2 + 1] += dispY * weight
            }
        }
    }

    private fun displaceLipsWarp(
        center: Point2D,
        radius: Float,
        volume: Float,
        shape: Float,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        count: Int
    ) {
        val r2 = radius * radius
        for (i in 0 until count) {
            val ox = origVerts[i * 2]
            val oy = origVerts[i * 2 + 1]
            val dx = ox - center.x
            val dy = oy - center.y
            val dist2 = dx * dx + dy * dy
            if (dist2 < r2) {
                val factor = 1f - dist2 / r2
                val weight = factor * factor
                // Volume expands upper and lower lips vertically
                val volumeDy = if (dy < 0) -volume * radius * 0.4f else volume * radius * 0.4f
                // Shape lifts corner smiles / cupid bow
                val shapeDy = -shape * radius * 0.25f * (1f - (dx / radius) * (dx / radius))
                deformedVerts[i * 2 + 1] += (volumeDy + shapeDy) * weight
            }
        }
    }

    private fun displaceChin(
        center: Point2D,
        radius: Float,
        strength: Float,
        faceW: Float,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        count: Int
    ) {
        val r2 = radius * radius
        for (i in 0 until count) {
            val ox = origVerts[i * 2]
            val oy = origVerts[i * 2 + 1]
            val dx = ox - center.x
            val dy = oy - center.y
            val dist2 = dx * dx + dy * dy
            if (dist2 < r2) {
                val factor = 1f - dist2 / r2
                val weight = factor * factor
                // Horizontal displacement narrows chin toward center (negative strength) or widens
                val dispX = -strength * dx * 0.5f
                // Vertical displacement lengthens (positive) or shortens (negative)
                val dispY = strength * faceW * 0.25f
                deformedVerts[i * 2] += dispX * weight
                deformedVerts[i * 2 + 1] += dispY * weight
            }
        }
    }

    private fun displaceWaist(
        center: Point2D,
        radius: Float,
        strength: Float,
        bodyW: Float,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        count: Int
    ) {
        val r2 = radius * radius
        for (i in 0 until count) {
            val ox = origVerts[i * 2]
            val oy = origVerts[i * 2 + 1]
            val dx = ox - center.x
            val dy = oy - center.y
            val dist2 = dx * dx + dy * dy
            if (dist2 < r2) {
                val factor = 1f - dist2 / r2
                val weight = factor * factor
                // Waist warp moves left side inward (+dx toward center if dx < 0) and right side inward (-dx toward center if dx > 0)
                // Strength < 0 narrows waist; strength > 0 widens waist
                val sideDispX = if (dx < 0) strength * bodyW * 0.4f else -strength * bodyW * 0.4f
                deformedVerts[i * 2] += sideDispX * weight
            }
        }
    }

    private fun displaceCylindricalAnkle(
        center: Point2D,
        radius: Float,
        strength: Float,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        count: Int
    ) {
        val r2 = radius * radius
        for (i in 0 until count) {
            val ox = origVerts[i * 2]
            val oy = origVerts[i * 2 + 1]
            val dx = ox - center.x
            val dy = oy - center.y
            val dist2 = dx * dx + dy * dy
            if (dist2 < r2) {
                val factor = 1f - dist2 / r2
                val weight = factor * factor
                // Compress/expand horizontally around the ankle joint center
                deformedVerts[i * 2] += (dx * strength * 0.4f) * weight
            }
        }
    }

    private fun displaceHeightStretch(
        topY: Float,
        bottomY: Float,
        spineX: Float,
        strength: Float,
        origVerts: FloatArray,
        deformedVerts: FloatArray,
        count: Int,
        imgH: Float
    ) {
        val rangeY = bottomY - topY
        if (rangeY <= 10f) return

        val stretchRadiusX = imgH * 0.4f
        val r2X = stretchRadiusX * stretchRadiusX

        for (i in 0 until count) {
            val ox = origVerts[i * 2]
            val oy = origVerts[i * 2 + 1]

            if (oy in topY..bottomY) {
                val dx = ox - spineX
                if (dx * dx < r2X) {
                    val falloffX = 1f - (dx * dx / r2X)
                    val progressY = (oy - topY) / rangeY
                    // Stretch linearly along leg bones / spine
                    val dispY = progressY * strength * rangeY * falloffX
                    deformedVerts[i * 2 + 1] += dispY
                }
            }
        }
    }

    // --- Facial Pixel Processing Engine (Skin Tone, Smoothing, Defect Removal, Hair Recolor) ---

    private fun applyFacialPixelEdits(
        source: Bitmap,
        people: List<DetectedPerson>,
        personParamsMap: Map<Int, PersonEditParams>
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = source.copy(Bitmap.Config.ARGB_8888, true)

        for (person in people) {
            val params = personParamsMap[person.id] ?: continue
            val face = person.face ?: continue
            val faceBounds = face.bounds

            // Expand face bounds slightly for skin & hair
            val left = max(0, (faceBounds.left - faceBounds.width() * 0.15f).toInt())
            val top = max(0, (faceBounds.top - faceBounds.height() * 0.35f).toInt())
            val right = min(width - 1, (faceBounds.right + faceBounds.width() * 0.15f).toInt())
            val bottom = min(height - 1, (faceBounds.bottom + faceBounds.height() * 0.15f).toInt())

            val patchW = right - left + 1
            val patchH = bottom - top + 1
            if (patchW <= 4 || patchH <= 4) continue

            val pixels = IntArray(patchW * patchH)
            output.getPixels(pixels, 0, patchW, left, top, patchW, patchH)

            // 1. Wrinkle Smoothing & Skin Defect Removal using Frequency Separation
            if (params.skinSmoothing > 0f || params.skinDefectRemoval > 0f) {
                applyFrequencySeparationSkinSmoothing(
                    pixels, patchW, patchH,
                    params.skinSmoothing / 100f,
                    params.skinDefectRemoval / 100f,
                    face, left, top
                )
            }

            // 1b. Advanced Skin Texture Adjustment (Blemish Removal vs Subtle Pore Details within segmented facial skin mask)
            if (params.skinTextureIntensity > 0f) {
                applyAdvancedSkinTexture(
                    pixels, patchW, patchH,
                    params.skinTextureMode,
                    params.skinTextureIntensity / 100f,
                    face, left, top
                )
            }

            // 2. Skin Tone Shift (Hue, Saturation, Lightness, Warmth strictly on skin)
            if (params.skinToneShift != 0f || params.skinToneWarmth != 0f) {
                applySkinToneAdjustment(
                    pixels, patchW, patchH,
                    params.skinToneShift / 100f,
                    params.skinToneWarmth / 100f,
                    face, left, top
                )
            }

            // 3. Hair Color Blend Mode Transfer
            if (params.hairColorArgb != null && params.hairColorIntensity > 0f) {
                applyHairColoring(
                    pixels, patchW, patchH,
                    params.hairColorArgb,
                    params.hairColorIntensity / 100f,
                    face, left, top
                )
            }

            output.setPixels(pixels, 0, patchW, left, top, patchW, patchH)
        }

        return output
    }

    /**
     * Surface blur / Bilateral-approximated Frequency Separation for skin smoothing:
     * - Low frequency base is smoothed to remove color unevenness, spots, wrinkles.
     * - High frequency texture (pores, fine details) is preserved using high-pass delta.
     */
    private fun applyFrequencySeparationSkinSmoothing(
        pixels: IntArray,
        w: Int,
        h: Int,
        smoothing: Float,
        defectRemoval: Float,
        face: com.example.model.FaceLandmarks,
        patchLeft: Int,
        patchTop: Int
    ) {
        val totalStrength = (smoothing * 0.7f + defectRemoval * 0.3f).coerceIn(0f, 1f)
        if (totalStrength <= 0.01f) return

        val blurRadius = (2 + (totalStrength * 6)).toInt()
        val blurred = boxBlurFast(pixels, w, h, blurRadius)

        val faceOval = face.faceOval
        val faceBounds = face.bounds

        val hsv = FloatArray(3)

        for (y in 0 until h) {
            val globalY = patchTop + y
            for (x in 0 until w) {
                val globalX = patchLeft + x
                val idx = y * w + x
                val origPixel = pixels[idx]

                // Check if pixel is likely skin
                val r = Color.red(origPixel)
                val g = Color.green(origPixel)
                val b = Color.blue(origPixel)

                if (isSkinPixel(r, g, b)) {
                    // Check soft distance to face bounds
                    val distWeight = getFaceContourWeight(globalX, globalY, faceBounds, faceOval)
                    if (distWeight > 0.05f) {
                        val blurPixel = blurred[idx]
                        val br = Color.red(blurPixel)
                        val bg = Color.green(blurPixel)
                        val bb = Color.blue(blurPixel)

                        // High frequency texture extraction
                        val highPassR = r - br
                        val highPassG = g - bg
                        val highPassB = b - bb

                        // Pore retention: preserve high pass texture while smoothing base
                        val textureRetention = 0.55f * (1f - defectRemoval * 0.45f)
                        val blendWeight = totalStrength * distWeight

                        val finalR = ((1f - blendWeight) * r + blendWeight * (br + highPassR * textureRetention)).toInt().coerceIn(0, 255)
                        val finalG = ((1f - blendWeight) * g + blendWeight * (bg + highPassG * textureRetention)).toInt().coerceIn(0, 255)
                        val finalB = ((1f - blendWeight) * b + blendWeight * (bb + highPassB * textureRetention)).toInt().coerceIn(0, 255)

                        pixels[idx] = Color.argb(Color.alpha(origPixel), finalR, finalG, finalB)
                    }
                }
            }
        }
    }

    private fun applySkinToneAdjustment(
        pixels: IntArray,
        w: Int,
        h: Int,
        toneShift: Float,
        warmth: Float,
        face: com.example.model.FaceLandmarks,
        patchLeft: Int,
        patchTop: Int
    ) {
        val hsv = FloatArray(3)
        for (y in 0 until h) {
            val globalY = patchTop + y
            for (x in 0 until w) {
                val idx = y * w + x
                val orig = pixels[idx]
                val r = Color.red(orig)
                val g = Color.green(orig)
                val b = Color.blue(orig)

                if (isSkinPixel(r, g, b)) {
                    val weight = getFaceContourWeight(patchLeft + x, globalY, face.bounds, face.faceOval)
                    if (weight > 0.05f) {
                        Color.colorToHSV(orig, hsv)
                        // Tone shift adjusts value/brightness & saturation
                        hsv[1] = (hsv[1] + toneShift * 0.2f * weight).coerceIn(0f, 1f)
                        hsv[2] = (hsv[2] + toneShift * 0.15f * weight).coerceIn(0f, 1f)

                        // Warmth adjusts red/yellow balance
                        val warmR = (r + warmth * 28f * weight).toInt().coerceIn(0, 255)
                        val warmG = (g + warmth * 12f * weight).toInt().coerceIn(0, 255)
                        val warmB = (b - warmth * 18f * weight).toInt().coerceIn(0, 255)

                        val intermediate = Color.HSVToColor(Color.alpha(orig), hsv)
                        val finalR = ((Color.red(intermediate) + warmR) / 2).coerceIn(0, 255)
                        val finalG = ((Color.green(intermediate) + warmG) / 2).coerceIn(0, 255)
                        val finalB = ((Color.blue(intermediate) + warmB) / 2).coerceIn(0, 255)

                        pixels[idx] = Color.argb(Color.alpha(orig), finalR, finalG, finalB)
                    }
                }
            }
        }
    }

    private fun applyHairColoring(
        pixels: IntArray,
        w: Int,
        h: Int,
        targetColor: Int,
        intensity: Float,
        face: com.example.model.FaceLandmarks,
        patchLeft: Int,
        patchTop: Int
    ) {
        val targetR = Color.red(targetColor)
        val targetG = Color.green(targetColor)
        val targetB = Color.blue(targetColor)

        // Hair is generally above face oval or along upper sides, non-skin pixels
        val faceBounds = face.bounds
        for (y in 0 until h) {
            val globalY = patchTop + y
            for (x in 0 until w) {
                val globalX = patchLeft + x
                val idx = y * w + x
                val orig = pixels[idx]
                val r = Color.red(orig)
                val g = Color.green(orig)
                val b = Color.blue(orig)

                // Hair region check: top region of head, not skin
                val isAboveForehead = globalY < (faceBounds.top + faceBounds.height() * 0.25f)
                val isSideOfHead = (globalX < faceBounds.left + faceBounds.width() * 0.15f || globalX > faceBounds.right - faceBounds.width() * 0.15f)
                        && globalY < faceBounds.bottom

                if ((isAboveForehead || isSideOfHead) && !isSkinPixel(r, g, b)) {
                    // Soft Light / Overlay blend
                    val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    val blendR = (r * (1f - intensity) + (targetR * lum) * intensity).toInt().coerceIn(0, 255)
                    val blendG = (g * (1f - intensity) + (targetG * lum) * intensity).toInt().coerceIn(0, 255)
                    val blendB = (b * (1f - intensity) + (targetB * lum) * intensity).toInt().coerceIn(0, 255)

                    pixels[idx] = Color.argb(Color.alpha(orig), blendR, blendG, blendB)
                }
            }
        }
    }

    private fun isSkinPixel(r: Int, g: Int, b: Int): Boolean {
        // Standard biometric skin color detector in RGB space
        return (r > 60 && g > 40 && b > 20 &&
                (r - g) > 10 && r > b &&
                (max(r, max(g, b)) - min(r, min(g, b))) > 12)
    }

    private fun getFaceContourWeight(x: Int, y: Int, faceBounds: RectF, faceOval: List<Point2D>): Float {
        val cx = faceBounds.centerX()
        val cy = faceBounds.centerY()
        val rx = faceBounds.width() * 0.55f
        val ry = faceBounds.height() * 0.65f

        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        val dist2 = dx * dx + dy * dy
        return if (dist2 < 1.0f) {
            1.0f - dist2 * 0.5f
        } else if (dist2 < 1.44f) {
            (1.44f - dist2) / 0.44f * 0.5f
        } else {
            0f
        }
    }

    private fun boxBlurFast(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val output = IntArray(w * h)
        val div = radius * 2 + 1

        // Horizontal pass
        val temp = IntArray(w * h)
        for (y in 0 until h) {
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (i in -radius..radius) {
                val px = pixels[y * w + i.coerceIn(0, w - 1)]
                sumR += Color.red(px)
                sumG += Color.green(px)
                sumB += Color.blue(px)
            }

            for (x in 0 until w) {
                temp[y * w + x] = Color.rgb(sumR / div, sumG / div, sumB / div)
                val leftPx = pixels[y * w + (x - radius).coerceIn(0, w - 1)]
                val rightPx = pixels[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                sumR += Color.red(rightPx) - Color.red(leftPx)
                sumG += Color.green(rightPx) - Color.green(leftPx)
                sumB += Color.blue(rightPx) - Color.blue(leftPx)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (i in -radius..radius) {
                val px = temp[i.coerceIn(0, h - 1) * w + x]
                sumR += Color.red(px)
                sumG += Color.green(px)
                sumB += Color.blue(px)
            }

            for (y in 0 until h) {
                output[y * w + x] = Color.rgb(sumR / div, sumG / div, sumB / div)
                val topPx = temp[(y - radius).coerceIn(0, h - 1) * w + x]
                val bottomPx = temp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                sumR += Color.red(bottomPx) - Color.red(topPx)
                sumG += Color.green(bottomPx) - Color.green(topPx)
                sumB += Color.blue(bottomPx) - Color.blue(topPx)
            }
        }

        return output
    }

    // --- Global Enhancements & Color Grading Presets ---

    private fun applyGlobalEnhancements(source: Bitmap, params: GlobalEditParams): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val colorMatrix = ColorMatrix()

        // 1. Exposure / Brightness (-100 to 100)
        if (params.exposure != 0f) {
            val brightnessMatrix = ColorMatrix()
            val shift = params.exposure * 1.28f
            brightnessMatrix.set(
                floatArrayOf(
                    1f, 0f, 0f, 0f, shift,
                    0f, 1f, 0f, 0f, shift,
                    0f, 0f, 1f, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            colorMatrix.postConcat(brightnessMatrix)
        }

        // 2. Contrast (-100 to 100)
        if (params.contrast != 0f) {
            val contrastMatrix = ColorMatrix()
            val scale = (params.contrast / 100f) + 1f
            val translate = (-0.5f * scale + 0.5f) * 255f
            contrastMatrix.set(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            colorMatrix.postConcat(contrastMatrix)
        }

        // 3. Saturation (-100 to 100)
        if (params.saturation != 0f) {
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation((params.saturation / 100f) + 1f)
            colorMatrix.postConcat(satMatrix)
        }

        // 4. Color Grading Preset
        val presetMatrix = when (params.colorGrade) {
            ColorGradePreset.ORIGINAL -> null
            ColorGradePreset.NATURAL_GLOW -> ColorMatrix(
                floatArrayOf(
                    1.05f, 0f, 0f, 0f, 8f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.96f, 0f, -2f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorGradePreset.WARM_SUNSET -> ColorMatrix(
                floatArrayOf(
                    1.15f, 0f, 0f, 0f, 15f,
                    0f, 1.05f, 0f, 0f, 8f,
                    0f, 0f, 0.88f, 0f, -12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorGradePreset.COOL_STUDIO -> ColorMatrix(
                floatArrayOf(
                    0.92f, 0f, 0f, 0f, -5f,
                    0f, 1.04f, 0f, 0f, 5f,
                    0f, 0f, 1.18f, 0f, 18f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorGradePreset.CINEMATIC -> ColorMatrix(
                floatArrayOf(
                    1.12f, 0.05f, -0.05f, 0f, 10f,
                    -0.02f, 1.08f, 0.02f, 0f, 5f,
                    -0.08f, 0.05f, 1.15f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorGradePreset.VINTAGE -> ColorMatrix(
                floatArrayOf(
                    0.95f, 0.1f, 0.05f, 0f, 20f,
                    0.05f, 0.90f, 0.05f, 0f, 15f,
                    0.05f, 0.1f, 0.80f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorGradePreset.NOIR -> {
                val m = ColorMatrix()
                m.setSaturation(0f)
                val c = ColorMatrix(
                    floatArrayOf(
                        1.25f, 0f, 0f, 0f, -25f,
                        0f, 1.25f, 0f, 0f, -25f,
                        0f, 0f, 1.25f, 0f, -25f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                m.postConcat(c)
                m
            }
        }

        if (presetMatrix != null) {
            colorMatrix.postConcat(presetMatrix)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Advanced Skin Texture Adjustment:
     * Operates specifically within the segmented facial skin mask (excluding eyes, eyebrows, lips).
     * - BLEMISH_REMOVAL: Local bilateral-guided surface smoothing to erase localized minor blemishes,
     *   red spots, and micro-imperfections without losing underlying facial structure.
     * - PORE_DETAILS: Synthesizes subtle, organic dermal pore micro-texture and enhances micro-contrast,
     *   giving a natural, crisp, non-plastic skin appearance.
     */
    private fun applyAdvancedSkinTexture(
        pixels: IntArray,
        w: Int,
        h: Int,
        mode: SkinTextureMode,
        intensity: Float,
        face: com.example.model.FaceLandmarks,
        patchLeft: Int,
        patchTop: Int
    ) {
        if (intensity <= 0.01f) return

        val faceBounds = face.bounds
        val faceOval = face.faceOval
        val eyeRadius = faceBounds.width() * 0.12f
        val eyeRadiusSq = eyeRadius * eyeRadius
        val mouthRadius = faceBounds.width() * 0.17f
        val mouthRadiusSq = mouthRadius * mouthRadius

        val leftEye = face.leftEyeCenter
        val rightEye = face.rightEyeCenter
        val mouth = face.mouthCenter

        val copy = pixels.clone()

        when (mode) {
            SkinTextureMode.BLEMISH_REMOVAL -> {
                // Bilateral edge-preserving defect removal
                val radius = 2
                val sigmaColor = 35.0f

                for (y in 0 until h) {
                    val gy = patchTop + y
                    for (x in 0 until w) {
                        val gx = patchLeft + x
                        val idx = y * w + x
                        val orig = copy[idx]

                        val r = Color.red(orig)
                        val g = Color.green(orig)
                        val b = Color.blue(orig)

                        if (!isSkinPixel(r, g, b)) continue

                        val contourWeight = getFaceContourWeight(gx, gy, faceBounds, faceOval)
                        if (contourWeight <= 0.05f) continue

                        // Exclude eyes and mouth
                        var featureWeight = 1.0f
                        if (leftEye != null) {
                            val ed2 = (gx - leftEye.x) * (gx - leftEye.x) + (gy - leftEye.y) * (gy - leftEye.y)
                            if (ed2 < eyeRadiusSq) featureWeight = 0f
                        }
                        if (rightEye != null && featureWeight > 0f) {
                            val ed2 = (gx - rightEye.x) * (gx - rightEye.x) + (gy - rightEye.y) * (gy - rightEye.y)
                            if (ed2 < eyeRadiusSq) featureWeight = 0f
                        }
                        if (mouth != null && featureWeight > 0f) {
                            val md2 = (gx - mouth.x) * (gx - mouth.x) + (gy - mouth.y) * (gy - mouth.y)
                            if (md2 < mouthRadiusSq) featureWeight = 0f
                        }
                        if (featureWeight <= 0f) continue

                        val mask = contourWeight * featureWeight * intensity

                        var sumR = 0f
                        var sumG = 0f
                        var sumB = 0f
                        var totalW = 0f

                        for (dy in -radius..radius) {
                            val ny = (y + dy).coerceIn(0, h - 1)
                            for (dx in -radius..radius) {
                                val nx = (x + dx).coerceIn(0, w - 1)
                                val nPix = copy[ny * w + nx]
                                val nr = Color.red(nPix)
                                val ng = Color.green(nPix)
                                val nb = Color.blue(nPix)

                                val d2 = (dx * dx + dy * dy).toFloat()
                                val cDiff = ((r - nr) * (r - nr) + (g - ng) * (g - ng) + (b - nb) * (b - nb)).toFloat()

                                val spatialW = exp(-d2 / 8f)
                                val rangeW = exp(-cDiff / (2f * sigmaColor * sigmaColor))
                                val weight = spatialW * rangeW

                                sumR += nr * weight
                                sumG += ng * weight
                                sumB += nb * weight
                                totalW += weight
                            }
                        }

                        if (totalW > 0f) {
                            val filteredR = sumR / totalW
                            val filteredG = sumG / totalW
                            val filteredB = sumB / totalW

                            val finalR = (r * (1f - mask) + filteredR * mask).toInt().coerceIn(0, 255)
                            val finalG = (g * (1f - mask) + filteredG * mask).toInt().coerceIn(0, 255)
                            val finalB = (b * (1f - mask) + filteredB * mask).toInt().coerceIn(0, 255)

                            pixels[idx] = Color.argb(Color.alpha(orig), finalR, finalG, finalB)
                        }
                    }
                }
            }

            SkinTextureMode.PORE_DETAILS -> {
                // High-pass micro-contrast enhancement & dermal micro-pore synthesis
                val blurred = boxBlurFast(copy, w, h, 2)

                for (y in 0 until h) {
                    val gy = patchTop + y
                    for (x in 0 until w) {
                        val gx = patchLeft + x
                        val idx = y * w + x
                        val orig = copy[idx]

                        val r = Color.red(orig)
                        val g = Color.green(orig)
                        val b = Color.blue(orig)

                        if (!isSkinPixel(r, g, b)) continue

                        val contourWeight = getFaceContourWeight(gx, gy, faceBounds, faceOval)
                        if (contourWeight <= 0.05f) continue

                        var featureWeight = 1.0f
                        if (leftEye != null) {
                            val ed2 = (gx - leftEye.x) * (gx - leftEye.x) + (gy - leftEye.y) * (gy - leftEye.y)
                            if (ed2 < eyeRadiusSq) featureWeight = 0f
                        }
                        if (rightEye != null && featureWeight > 0f) {
                            val ed2 = (gx - rightEye.x) * (gx - rightEye.x) + (gy - rightEye.y) * (gy - rightEye.y)
                            if (ed2 < eyeRadiusSq) featureWeight = 0f
                        }
                        if (mouth != null && featureWeight > 0f) {
                            val md2 = (gx - mouth.x) * (gx - mouth.x) + (gy - mouth.y) * (gy - mouth.y)
                            if (md2 < mouthRadiusSq) featureWeight = 0f
                        }
                        if (featureWeight <= 0f) continue

                        val mask = contourWeight * featureWeight * intensity

                        val bPix = blurred[idx]
                        val br = Color.red(bPix)
                        val bg = Color.green(bPix)
                        val bb = Color.blue(bPix)

                        // 1. High pass component of existing dermal texture
                        val hpR = (r - br).toFloat()
                        val hpG = (g - bg).toFloat()
                        val hpB = (b - bb).toFloat()

                        // 2. Realistic micro-pore cellular stippling pattern
                        val freq = 1.8f
                        val phaseX = gx * freq
                        val phaseY = gy * freq
                        val pseudoNoise = ((sin(gx * 12.9898f + gy * 78.233f) * 43758.5453f) % 1.0f) - 0.5f
                        val porePattern = (sin(phaseX) * cos(phaseY) * 0.6f + pseudoNoise * 0.4f) * 22f

                        // 3. Modulate pore stippling: subtle in shadows/highlights, prominent in midtones (cheeks/nose)
                        val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                        val midtoneWeight = (1.0f - (lum - 0.55f) * (lum - 0.55f) * 4.0f).coerceIn(0.2f, 1.0f)

                        val delta = (porePattern * midtoneWeight + (hpR * 0.35f)) * mask

                        val finalR = (r + delta).toInt().coerceIn(0, 255)
                        val finalG = (g + delta).toInt().coerceIn(0, 255)
                        val finalB = (b + delta).toInt().coerceIn(0, 255)

                        pixels[idx] = Color.argb(Color.alpha(orig), finalR, finalG, finalB)
                    }
                }
            }
        }
    }

    /**
     * Composites foreground photo with a generated background image.
     * Uses segmentation mask with soft feathering and ambient lighting color harmonization
     * for seamless edge integration.
     */
    fun compositeWithBackground(
        foregroundBitmap: Bitmap,
        backgroundBitmap: Bitmap,
        segmentationMask: SegmentationMask?,
        people: List<DetectedPerson>
    ): Bitmap {
        val w = foregroundBitmap.width
        val h = foregroundBitmap.height

        // 1. Scale background to match foreground dimensions with aspect-fill center crop
        val scaledBg = if (backgroundBitmap.width == w && backgroundBitmap.height == h) {
            backgroundBitmap
        } else {
            val scale = max(w.toFloat() / backgroundBitmap.width, h.toFloat() / backgroundBitmap.height)
            val sw = max(w, (backgroundBitmap.width * scale).toInt())
            val sh = max(h, (backgroundBitmap.height * scale).toInt())
            val temp = Bitmap.createScaledBitmap(backgroundBitmap, sw, sh, true)
            val xOff = max(0, (sw - w) / 2)
            val yOff = max(0, (sh - h) / 2)
            val cropped = Bitmap.createBitmap(temp, xOff, yOff, w, h)
            if (temp != backgroundBitmap && temp != cropped) temp.recycle()
            cropped
        }

        // 2. Build foreground alpha mask
        val alphaMask = buildAlphaMask(w, h, segmentationMask, people)

        // 3. Smooth / feather edge boundary to eliminate hard cutout fringes
        featherAlphaMask(alphaMask, w, h)

        // 4. Sample background ambient lighting for environmental edge rim harmonization
        var bgSumR = 0L
        var bgSumG = 0L
        var bgSumB = 0L
        val sampleStep = 16
        var samplesCount = 0

        val bgPixels = IntArray(w * h)
        scaledBg.getPixels(bgPixels, 0, w, 0, 0, w, h)

        val fgPixels = IntArray(w * h)
        foregroundBitmap.getPixels(fgPixels, 0, w, 0, 0, w, h)

        for (i in 0 until (w * h) step sampleStep) {
            val p = bgPixels[i]
            bgSumR += Color.red(p)
            bgSumG += Color.green(p)
            bgSumB += Color.blue(p)
            samplesCount++
        }
        val bgAvgR = if (samplesCount > 0) (bgSumR / samplesCount).toInt() else 128
        val bgAvgG = if (samplesCount > 0) (bgSumG / samplesCount).toInt() else 128
        val bgAvgB = if (samplesCount > 0) (bgSumB / samplesCount).toInt() else 128

        // 5. Composite pixels with ambient light harmonization
        val outputPixels = IntArray(w * h)

        for (i in 0 until (w * h)) {
            val alpha = alphaMask[i]
            val fg = fgPixels[i]
            val bg = bgPixels[i]

            if (alpha >= 0.99f) {
                outputPixels[i] = fg
            } else if (alpha <= 0.01f) {
                outputPixels[i] = bg
            } else {
                var fgR = Color.red(fg)
                var fgG = Color.green(fg)
                var fgB = Color.blue(fg)

                // Environmental light bleed on silhouette boundary (feathered rim lighting)
                val rimFactor = (1f - alpha) * 0.12f
                fgR = ((1f - rimFactor) * fgR + rimFactor * bgAvgR).toInt().coerceIn(0, 255)
                fgG = ((1f - rimFactor) * fgG + rimFactor * bgAvgG).toInt().coerceIn(0, 255)
                fgB = ((1f - rimFactor) * fgB + rimFactor * bgAvgB).toInt().coerceIn(0, 255)

                val bgR = Color.red(bg)
                val bgG = Color.green(bg)
                val bgB = Color.blue(bg)

                val outR = (fgR * alpha + bgR * (1f - alpha)).toInt().coerceIn(0, 255)
                val outG = (fgG * alpha + bgG * (1f - alpha)).toInt().coerceIn(0, 255)
                val outB = (fgB * alpha + bgB * (1f - alpha)).toInt().coerceIn(0, 255)

                outputPixels[i] = Color.rgb(outR, outG, outB)
            }
        }

        val resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outputPixels, 0, w, 0, 0, w, h)
        return resultBitmap
    }

    private fun buildFallbackAlphaMask(
        mask: FloatArray,
        w: Int,
        h: Int,
        people: List<DetectedPerson>
    ) {
        if (people.isEmpty()) {
            // Center oval portrait default mask
            val cx = w * 0.5f
            val cy = h * 0.5f
            val rx = w * 0.38f
            val ry = h * 0.45f
            for (y in 0 until h) {
                val dy = (y - cy) / ry
                for (x in 0 until w) {
                    val dx = (x - cx) / rx
                    val d2 = dx * dx + dy * dy
                    mask[y * w + x] = if (d2 < 0.8f) 1.0f else if (d2 < 1.15f) ((1.15f - d2) / 0.35f) else 0f
                }
            }
            return
        }

        for (person in people) {
            val bounds = person.bounds
            val padX = bounds.width() * 0.08f
            val padY = bounds.height() * 0.06f
            val bLeft = max(0f, bounds.left - padX)
            val bTop = max(0f, bounds.top - padY)
            val bRight = min(w.toFloat(), bounds.right + padX)
            val bBottom = min(h.toFloat(), bounds.bottom + padY)

            val cx = (bLeft + bRight) / 2f
            val cy = (bTop + bBottom) / 2f
            val rx = (bRight - bLeft) / 2f
            val ry = (bBottom - bTop) / 2f

            for (y in bTop.toInt() until bBottom.toInt()) {
                val dy = (y - cy) / ry
                for (x in bLeft.toInt() until bRight.toInt()) {
                    val dx = (x - cx) / rx
                    val d2 = dx * dx + dy * dy
                    val alpha = if (d2 < 0.75f) 1.0f else if (d2 < 1.1f) ((1.1f - d2) / 0.35f) else 0f
                    val idx = y * w + x
                    mask[idx] = max(mask[idx], alpha)
                }
            }
        }
    }

    private fun featherAlphaMask(mask: FloatArray, w: Int, h: Int) {
        val temp = mask.clone()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val centerVal = temp[idx]
                // Only feather edge pixels
                if (centerVal in 0.05f..0.95f) {
                    val sum = temp[(y - 1) * w + x] + temp[(y + 1) * w + x] +
                            temp[y * w + (x - 1)] + temp[y * w + (x + 1)] + centerVal
                    mask[idx] = sum / 5.0f
                }
            }
        }
    }

    private fun buildAlphaMask(
        w: Int,
        h: Int,
        segmentationMask: SegmentationMask?,
        people: List<DetectedPerson>
    ): FloatArray {
        val alphaMask = FloatArray(w * h)

        if (segmentationMask != null) {
            try {
                val buffer = segmentationMask.buffer
                buffer.rewind()
                val maskW = segmentationMask.width
                val maskH = segmentationMask.height
                val rawConfidence = FloatArray(maskW * maskH)
                for (i in 0 until maskW * maskH) {
                    rawConfidence[i] = buffer.float
                }

                val scaleX = maskW.toFloat() / w
                val scaleY = maskH.toFloat() / h

                for (y in 0 until h) {
                    val srcY = y * scaleY
                    val y0 = srcY.toInt().coerceIn(0, maskH - 1)
                    val y1 = (y0 + 1).coerceIn(0, maskH - 1)
                    val yWeight = srcY - y0

                    for (x in 0 until w) {
                        val srcX = x * scaleX
                        val x0 = srcX.toInt().coerceIn(0, maskW - 1)
                        val x1 = (x0 + 1).coerceIn(0, maskW - 1)
                        val xWeight = srcX - x0

                        val top = rawConfidence[y0 * maskW + x0] * (1f - xWeight) + rawConfidence[y0 * maskW + x1] * xWeight
                        val bottom = rawConfidence[y1 * maskW + x0] * (1f - xWeight) + rawConfidence[y1 * maskW + x1] * xWeight
                        val conf = top * (1f - yWeight) + bottom * yWeight

                        alphaMask[y * w + x] = ((conf - 0.25f) / 0.5f).coerceIn(0f, 1f)
                    }
                }
                return alphaMask
            } catch (_: Exception) {
                buildFallbackAlphaMask(alphaMask, w, h, people)
            }
        } else {
            buildFallbackAlphaMask(alphaMask, w, h, people)
        }
        return alphaMask
    }

    private fun applyBackgroundPortraitEffects(
        source: Bitmap,
        segmentationMask: SegmentationMask?,
        people: List<DetectedPerson>,
        blurStrength: Float,
        brightnessShift: Float
    ): Bitmap {
        val w = source.width
        val h = source.height
        val alphaMask = buildAlphaMask(w, h, segmentationMask, people)
        featherAlphaMask(alphaMask, w, h)

        val blurredBmp: Bitmap? = if (blurStrength > 0f) {
            val down = 3
            val sw = max(1, w / down)
            val sh = max(1, h / down)
            val small = Bitmap.createScaledBitmap(source, sw, sh, true)
            val radius = ((blurStrength / 100f) * 14f + 2f).toInt()
            val blurredSmall = fastBoxBlur(small, radius)
            val upscaled = Bitmap.createScaledBitmap(blurredSmall, w, h, true)
            if (small != blurredSmall) small.recycle()
            blurredSmall.recycle()
            upscaled
        } else null

        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val bgPixels = if (blurredBmp != null) {
            val bp = IntArray(w * h)
            blurredBmp.getPixels(bp, 0, w, 0, 0, w, h)
            blurredBmp.recycle()
            bp
        } else srcPixels

        val outPixels = IntArray(w * h)
        val brightMultiplier = (brightnessShift / 100f) + 1f

        for (i in 0 until (w * h)) {
            val alpha = alphaMask[i]
            if (alpha >= 0.99f) {
                outPixels[i] = srcPixels[i]
            } else {
                val bg = bgPixels[i]
                val br = (Color.red(bg) * brightMultiplier).toInt().coerceIn(0, 255)
                val bgCol = (Color.green(bg) * brightMultiplier).toInt().coerceIn(0, 255)
                val bb = (Color.blue(bg) * brightMultiplier).toInt().coerceIn(0, 255)

                if (alpha <= 0.01f) {
                    outPixels[i] = Color.rgb(br, bgCol, bb)
                } else {
                    val fg = srcPixels[i]
                    val fr = Color.red(fg)
                    val fgG = Color.green(fg)
                    val fb = Color.blue(fg)

                    val r = (fr * alpha + br * (1f - alpha)).toInt().coerceIn(0, 255)
                    val g = (fgG * alpha + bgCol * (1f - alpha)).toInt().coerceIn(0, 255)
                    val b = (fb * alpha + bb * (1f - alpha)).toInt().coerceIn(0, 255)
                    outPixels[i] = Color.rgb(r, g, b)
                }
            }
        }

        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(outPixels, 0, w, 0, 0, w, h)
        return res
    }

    private fun fastBoxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)

        val outPix = IntArray(w * h)
        val r = radius.coerceIn(1, 25)
        val div = r + r + 1

        val temp = IntArray(w * h)
        for (y in 0 until h) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            val rowStart = y * w

            for (i in -r..r) {
                val px = pix[rowStart + i.coerceIn(0, w - 1)]
                sumR += Color.red(px)
                sumG += Color.green(px)
                sumB += Color.blue(px)
            }

            for (x in 0 until w) {
                temp[rowStart + x] = Color.rgb(sumR / div, sumG / div, sumB / div)
                val leftPx = pix[rowStart + (x - r).coerceIn(0, w - 1)]
                val rightPx = pix[rowStart + (x + r + 1).coerceIn(0, w - 1)]
                sumR += Color.red(rightPx) - Color.red(leftPx)
                sumG += Color.green(rightPx) - Color.green(leftPx)
                sumB += Color.blue(rightPx) - Color.blue(leftPx)
            }
        }

        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -r..r) {
                val px = temp[i.coerceIn(0, h - 1) * w + x]
                sumR += Color.red(px)
                sumG += Color.green(px)
                sumB += Color.blue(px)
            }

            for (y in 0 until h) {
                outPix[y * w + x] = Color.rgb(sumR / div, sumG / div, sumB / div)
                val topPx = temp[(y - r).coerceIn(0, h - 1) * w + x]
                val btmPx = temp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sumR += Color.red(btmPx) - Color.red(topPx)
                sumG += Color.green(btmPx) - Color.green(topPx)
                sumB += Color.blue(btmPx) - Color.blue(topPx)
            }
        }

        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(outPix, 0, w, 0, 0, w, h)
        return res
    }
}

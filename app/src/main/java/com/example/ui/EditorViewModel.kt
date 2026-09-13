package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiEditResult
import com.example.ai.GeminiGenerativeEditor
import com.example.cv.MorphWarpEngine
import com.example.cv.VisionDetector
import com.example.data.ImageExportHelper
import com.example.model.ColorGradePreset
import com.example.model.DetectedPerson
import com.example.model.GlobalEditParams
import com.example.model.PersonEditParams
import com.google.mlkit.vision.segmentation.SegmentationMask
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EditorViewModel : ViewModel() {

    private val visionDetector = VisionDetector()
    private val morphWarpEngine = MorphWarpEngine()
    private val geminiEditor = GeminiGenerativeEditor()

    private var basePhotoBitmap: Bitmap? = null

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _detectedPeople = MutableStateFlow<List<DetectedPerson>>(emptyList())
    val detectedPeople: StateFlow<List<DetectedPerson>> = _detectedPeople.asStateFlow()

    private val _segmentationMask = MutableStateFlow<SegmentationMask?>(null)

    private val _selectedPersonId = MutableStateFlow<Int?>(null)
    val selectedPersonId: StateFlow<Int?> = _selectedPersonId.asStateFlow()

    private val _personParamsMap = MutableStateFlow<Map<Int, PersonEditParams>>(emptyMap())
    val personParamsMap: StateFlow<Map<Int, PersonEditParams>> = _personParamsMap.asStateFlow()

    private val _globalParams = MutableStateFlow(GlobalEditParams())
    val globalParams: StateFlow<GlobalEditParams> = _globalParams.asStateFlow()

    private val _isDetecting = MutableStateFlow(false)
    val isDetecting: StateFlow<Boolean> = _isDetecting.asStateFlow()

    private val _isRendering = MutableStateFlow(false)
    val isRendering: StateFlow<Boolean> = _isRendering.asStateFlow()

    private val _showCompare = MutableStateFlow(false)
    val showCompare: StateFlow<Boolean> = _showCompare.asStateFlow()

    private val _showOverlays = MutableStateFlow(true)
    val showOverlays: StateFlow<Boolean> = _showOverlays.asStateFlow()

    private val _geminiStatus = MutableStateFlow<String?>(null)
    val geminiStatus: StateFlow<String?> = _geminiStatus.asStateFlow()

    private val _isGeminiLoading = MutableStateFlow(false)
    val isGeminiLoading: StateFlow<Boolean> = _isGeminiLoading.asStateFlow()

    private val _isBackgroundGenerating = MutableStateFlow(false)
    val isBackgroundGenerating: StateFlow<Boolean> = _isBackgroundGenerating.asStateFlow()

    private val _hasReplacedBackground = MutableStateFlow(false)
    val hasReplacedBackground: StateFlow<Boolean> = _hasReplacedBackground.asStateFlow()

    private val _backgroundStatus = MutableStateFlow<String?>(null)
    val backgroundStatus: StateFlow<String?> = _backgroundStatus.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    private var renderJob: Job? = null

    fun setImage(bitmap: Bitmap) {
        basePhotoBitmap = bitmap
        _originalBitmap.value = bitmap
        _previewBitmap.value = bitmap
        _personParamsMap.value = emptyMap()
        _globalParams.value = GlobalEditParams()
        _hasReplacedBackground.value = false
        _backgroundStatus.value = null

        viewModelScope.launch {
            _isDetecting.value = true
            try {
                val (people, segMask) = visionDetector.detectPeopleAndFeatures(bitmap)
                _detectedPeople.value = people
                _segmentationMask.value = segMask

                // Initialize default edit params for each detected person
                val initialMap = people.associate { it.id to PersonEditParams() }
                _personParamsMap.value = initialMap

                // Automatically select first person if available
                if (people.isNotEmpty()) {
                    _selectedPersonId.value = people.first().id
                } else {
                    _selectedPersonId.value = null
                }
            } catch (e: Exception) {
                // Keep default
            } finally {
                _isDetecting.value = false
            }
        }
    }

    fun selectPerson(personId: Int?) {
        _selectedPersonId.value = personId
    }

    fun toggleCompare(show: Boolean) {
        _showCompare.value = show
    }

    fun toggleOverlays() {
        _showOverlays.value = !_showOverlays.value
    }

    fun updateCurrentPersonParams(update: (PersonEditParams) -> PersonEditParams) {
        val personId = _selectedPersonId.value ?: return
        val current = _personParamsMap.value[personId] ?: PersonEditParams()
        val updated = update(current)

        _personParamsMap.value = _personParamsMap.value.toMutableMap().apply {
            put(personId, updated)
        }
        triggerRender()
    }

    fun updateGlobalParams(update: (GlobalEditParams) -> GlobalEditParams) {
        _globalParams.value = update(_globalParams.value)
        triggerRender()
    }

    fun resetSelectedPerson() {
        val personId = _selectedPersonId.value ?: return
        _personParamsMap.value = _personParamsMap.value.toMutableMap().apply {
            put(personId, PersonEditParams())
        }
        triggerRender()
    }

    fun resetGlobal() {
        _globalParams.value = GlobalEditParams()
        triggerRender()
    }

    fun resetAll() {
        val clearedMap = _detectedPeople.value.associate { it.id to PersonEditParams() }
        _personParamsMap.value = clearedMap
        _globalParams.value = GlobalEditParams()
        triggerRender()
    }

    private fun triggerRender() {
        val orig = _originalBitmap.value ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            // Short debounce for rapid slider drags
            delay(16)
            _isRendering.value = true
            try {
                val rendered = morphWarpEngine.applyEdits(
                    originalBitmap = orig,
                    people = _detectedPeople.value,
                    personParamsMap = _personParamsMap.value,
                    globalParams = _globalParams.value,
                    segmentationMask = _segmentationMask.value
                )
                _previewBitmap.value = rendered
            } catch (e: Exception) {
                // keep current preview
            } finally {
                _isRendering.value = false
            }
        }
    }

    /**
     * Generates a new background with Gemini API and seamlessly composites it
     * with the original foreground using the segmentation mask.
     */
    fun replaceBackground(prompt: String) {
        val source = basePhotoBitmap ?: _originalBitmap.value ?: return
        viewModelScope.launch {
            _isBackgroundGenerating.value = true
            _backgroundStatus.value = "Генерация нового фона через Gemini ИИ..."

            when (val result = geminiEditor.generateBackground(prompt, source.width, source.height)) {
                is GeminiEditResult.ImageSuccess -> {
                    try {
                        val composited = morphWarpEngine.compositeWithBackground(
                            foregroundBitmap = source,
                            backgroundBitmap = result.bitmap,
                            segmentationMask = _segmentationMask.value,
                            people = _detectedPeople.value
                        )
                        _originalBitmap.value = composited
                        _hasReplacedBackground.value = true
                        _backgroundStatus.value = "Фон успешно заменен: ${result.description}"
                        triggerRender()
                    } catch (e: Exception) {
                        _backgroundStatus.value = "Ошибка объединения с фоном: ${e.localizedMessage}"
                    }
                }
                is GeminiEditResult.TextSuccess -> {
                    _backgroundStatus.value = result.message
                }
                is GeminiEditResult.Error -> {
                    _backgroundStatus.value = "Ошибка генерации фона: ${result.message}"
                }
            }
            _isBackgroundGenerating.value = false
        }
    }

    /**
     * Composites a custom image (from gallery or preset) as the new background.
     */
    fun replaceBackgroundWithBitmap(customBg: Bitmap) {
        val source = basePhotoBitmap ?: _originalBitmap.value ?: return
        viewModelScope.launch {
            _isBackgroundGenerating.value = true
            _backgroundStatus.value = "Объединение с выбранным фоном..."
            try {
                val composited = morphWarpEngine.compositeWithBackground(
                    foregroundBitmap = source,
                    backgroundBitmap = customBg,
                    segmentationMask = _segmentationMask.value,
                    people = _detectedPeople.value
                )
                _originalBitmap.value = composited
                _hasReplacedBackground.value = true
                _backgroundStatus.value = "Фон успешно установлен"
                triggerRender()
            } catch (e: Exception) {
                _backgroundStatus.value = "Ошибка объединения: ${e.localizedMessage}"
            }
            _isBackgroundGenerating.value = false
        }
    }

    /**
     * Composites a built-in scenic preset immediately without network latency.
     */
    fun replaceBackgroundWithPreset(presetPrompt: String) {
        val source = basePhotoBitmap ?: _originalBitmap.value ?: return
        viewModelScope.launch {
            _isBackgroundGenerating.value = true
            _backgroundStatus.value = "Применение фона..."
            try {
                val presetBmp = geminiEditor.createAlgorithmicScenery(presetPrompt, source.width, source.height)
                val composited = morphWarpEngine.compositeWithBackground(
                    foregroundBitmap = source,
                    backgroundBitmap = presetBmp,
                    segmentationMask = _segmentationMask.value,
                    people = _detectedPeople.value
                )
                _originalBitmap.value = composited
                _hasReplacedBackground.value = true
                _backgroundStatus.value = "Фон применен: $presetPrompt"
                triggerRender()
            } catch (e: Exception) {
                _backgroundStatus.value = "Ошибка: ${e.localizedMessage}"
            }
            _isBackgroundGenerating.value = false
        }
    }

    fun resetBackground() {
        val base = basePhotoBitmap ?: return
        _originalBitmap.value = base
        _hasReplacedBackground.value = false
        _backgroundStatus.value = "Исходный фон восстановлен"
        triggerRender()
    }

    fun clearBackgroundStatus() {
        _backgroundStatus.value = null
    }

    fun runGeminiGenerativeEdit(prompt: String) {
        val current = _previewBitmap.value ?: _originalBitmap.value ?: return
        viewModelScope.launch {
            _isGeminiLoading.value = true
            _geminiStatus.value = "Генерация ИИ-ретуши через Gemini..."
            when (val result = geminiEditor.performGenerativeEdit(current, prompt)) {
                is GeminiEditResult.ImageSuccess -> {
                    _originalBitmap.value = result.bitmap
                    _previewBitmap.value = result.bitmap
                    _geminiStatus.value = "ИИ-ретушь успешно применена: ${result.description}"
                    // Re-detect features for newly generated image
                    _isDetecting.value = true
                    try {
                        val (people, segMask) = visionDetector.detectPeopleAndFeatures(result.bitmap)
                        _detectedPeople.value = people
                        _segmentationMask.value = segMask
                        _personParamsMap.value = people.associate { it.id to PersonEditParams() }
                    } finally {
                        _isDetecting.value = false
                    }
                }
                is GeminiEditResult.TextSuccess -> {
                    _geminiStatus.value = "ИИ Анализ: ${result.message}"
                }
                is GeminiEditResult.Error -> {
                    _geminiStatus.value = result.message
                }
            }
            _isGeminiLoading.value = false
        }
    }

    fun clearGeminiStatus() {
        _geminiStatus.value = null
    }

    fun saveImageToGallery(context: Context) {
        val bitmap = _previewBitmap.value ?: return
        viewModelScope.launch {
            _exportStatus.value = "Сохранение в Галерею..."
            val result = ImageExportHelper.saveBitmapToGallery(context, bitmap)
            result.onSuccess {
                _exportStatus.value = "Успешно сохранено в Pictures/MorphVision!"
            }.onFailure { e ->
                _exportStatus.value = "Ошибка сохранения: ${e.localizedMessage}"
            }
        }
    }

    fun shareCurrentBitmap(context: Context) {
        val bitmap = _previewBitmap.value ?: return
        viewModelScope.launch {
            ImageExportHelper.shareBitmap(context, bitmap)
        }
    }

    fun clearExportStatus() {
        _exportStatus.value = null
    }
}


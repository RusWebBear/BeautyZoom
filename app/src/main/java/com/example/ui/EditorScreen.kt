package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ColorGradePreset
import com.example.model.DetectedPerson
import com.example.model.GlobalEditParams
import com.example.model.PersonEditParams
import com.example.model.SkinTextureMode
import kotlin.math.roundToInt

enum class EditCategoryTab(val title: String, val icon: @Composable () -> Unit) {
    FACE("Лицо", { Icon(Icons.Default.Face, contentDescription = null) }),
    BODY("Тело", { Icon(Icons.Default.FitnessCenter, contentDescription = null) }),
    BACKGROUND("Фон", { Icon(Icons.Default.Image, contentDescription = null) }),
    GLOBAL("Цвет", { Icon(Icons.Default.Tune, contentDescription = null) }),
    AI_MAGIC("ИИ", { Icon(Icons.Default.AutoAwesome, contentDescription = null) })
}

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val originalBitmap by viewModel.originalBitmap.collectAsState()
    val previewBitmap by viewModel.previewBitmap.collectAsState()
    val detectedPeople by viewModel.detectedPeople.collectAsState()
    val selectedPersonId by viewModel.selectedPersonId.collectAsState()
    val personParamsMap by viewModel.personParamsMap.collectAsState()
    val globalParams by viewModel.globalParams.collectAsState()
    val isDetecting by viewModel.isDetecting.collectAsState()
    val isRendering by viewModel.isRendering.collectAsState()
    val showCompare by viewModel.showCompare.collectAsState()
    val showOverlays by viewModel.showOverlays.collectAsState()
    val geminiStatus by viewModel.geminiStatus.collectAsState()
    val isGeminiLoading by viewModel.isGeminiLoading.collectAsState()
    val isBackgroundGenerating by viewModel.isBackgroundGenerating.collectAsState()
    val hasReplacedBackground by viewModel.hasReplacedBackground.collectAsState()
    val backgroundStatus by viewModel.backgroundStatus.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()

    var selectedTab by remember { mutableStateOf(EditCategoryTab.FACE) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Launcher for selecting a custom background photo from Gallery
    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        viewModel.replaceBackgroundWithBitmap(bmp)
                    }
                }
            } catch (e: Exception) {
                // Ignore decoding errors
            }
        }
    }

    LaunchedEffect(exportStatus) {
        exportStatus?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearExportStatus()
        }
    }

    val currentPerson = detectedPeople.find { it.id == selectedPersonId }
    val currentPersonParams = selectedPersonId?.let { personParamsMap[it] } ?: PersonEditParams()

    // Compare button interaction
    val compareInteractionSource = remember { MutableInteractionSource() }
    val isComparePressed by compareInteractionSource.collectIsPressedAsState()

    LaunchedEffect(isComparePressed) {
        viewModel.toggleCompare(isComparePressed)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            EditorTopBar(
                isDetecting = isDetecting,
                isRendering = isRendering,
                showOverlays = showOverlays,
                onToggleOverlays = { viewModel.toggleOverlays() },
                compareInteractionSource = compareInteractionSource,
                onReset = { viewModel.resetAll() },
                onSave = { viewModel.saveImageToGallery(context) },
                onShare = { viewModel.shareCurrentBitmap(context) },
                onBack = onBack
            )

            // Detected Person Selector Bar
            PersonSelectorBar(
                people = detectedPeople,
                selectedPersonId = selectedPersonId,
                onSelectPerson = { viewModel.selectPerson(it) }
            )

            // Interactive Viewport / Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF08080C)),
                contentAlignment = Alignment.Center
            ) {
                val displayBitmap = if (showCompare) originalBitmap else previewBitmap
                if (displayBitmap != null) {
                    ZoomableEditorCanvas(
                        bitmap = displayBitmap,
                        people = detectedPeople,
                        selectedPersonId = selectedPersonId,
                        showOverlays = showOverlays && !showCompare,
                        onPersonTapped = { personId ->
                            viewModel.selectPerson(personId)
                        }
                    )
                }

                if (showCompare) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Исходное (до)",
                            color = Color(0xFFFFD166),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isDetecting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                            .padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFF00EBFF),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Поиск людей и контуров...",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Bottom Editing Sheet / Category Controls
            EditorBottomControls(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                currentPerson = currentPerson,
                currentPersonParams = currentPersonParams,
                globalParams = globalParams,
                isGeminiLoading = isGeminiLoading,
                geminiStatus = geminiStatus,
                isBackgroundGenerating = isBackgroundGenerating,
                hasReplacedBackground = hasReplacedBackground,
                backgroundStatus = backgroundStatus,
                onUpdatePersonParams = { update ->
                    viewModel.updateCurrentPersonParams(update)
                },
                onUpdateGlobalParams = { update ->
                    viewModel.updateGlobalParams(update)
                },
                onResetPersonParams = { viewModel.resetSelectedPerson() },
                onResetGlobalParams = { viewModel.resetGlobal() },
                onResetBokeh = {
                    viewModel.updateGlobalParams { it.copy(backgroundBlur = 0f, backgroundBrightness = 0f) }
                },
                onPickBackgroundFromGallery = { bgPickerLauncher.launch("image/*") },
                onSelectBackgroundPreset = { preset -> viewModel.replaceBackgroundWithPreset(preset) },
                onReplaceBackground = { prompt -> viewModel.replaceBackground(prompt) },
                onResetBackground = { viewModel.resetBackground() },
                onClearBackgroundStatus = { viewModel.clearBackgroundStatus() },
                onRunGeminiEdit = { prompt -> viewModel.runGeminiGenerativeEdit(prompt) },
                onClearGeminiStatus = { viewModel.clearGeminiStatus() }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

@Composable
fun EditorTopBar(
    isDetecting: Boolean,
    isRendering: Boolean,
    showOverlays: Boolean,
    onToggleOverlays: () -> Unit,
    compareInteractionSource: MutableInteractionSource,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = Color(0xFF13131A)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = Color.White
                    )
                }

                Text(
                    text = "MorphVision",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (isRendering) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF7F5AF0)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Toggle detection overlays
                IconButton(onClick = onToggleOverlays) {
                    Icon(
                        imageVector = if (showOverlays) Icons.Default.Layers else Icons.Default.LayersClear,
                        contentDescription = "Сетка и точки",
                        tint = if (showOverlays) Color(0xFF00EBFF) else Color.White.copy(alpha = 0.5f)
                    )
                }

                // Hold to compare Before / After
                Box(
                    modifier = Modifier
                        .clickable(
                            interactionSource = compareInteractionSource,
                            indication = null,
                            onClick = {}
                        )
                        .background(Color(0xFF262638), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Сравнить",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Сравнить",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Reset
                IconButton(onClick = onReset) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Сброс всего",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }

                // Share
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Поделиться",
                        tint = Color.White
                    )
                }

                // Save
                IconButton(
                    onClick = onSave,
                    modifier = Modifier
                        .background(Color(0xFF7F5AF0), RoundedCornerShape(10.dp))
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SaveAlt,
                        contentDescription = "Сохранить в Галерею",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PersonSelectorBar(
    people: List<DetectedPerson>,
    selectedPersonId: Int?,
    onSelectPerson: (Int?) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF181822)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Объект:",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 8.dp)
            )

            // Whole Frame chip
            FilterChip(
                selected = selectedPersonId == null,
                onClick = { onSelectPerson(null) },
                label = { Text("Весь кадр", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF7F5AF0),
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF262638),
                    labelColor = Color.White.copy(alpha = 0.8f)
                ),
                modifier = Modifier.padding(end = 6.dp)
            )

            // Individual detected person chips
            people.forEach { person ->
                val isSelected = person.id == selectedPersonId
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectPerson(person.id) },
                    label = { Text(person.label, fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (person.hasFace) Icons.Default.Face else Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF00EBFF),
                        selectedLabelColor = Color.Black,
                        selectedLeadingIconColor = Color.Black,
                        containerColor = Color(0xFF262638),
                        labelColor = Color.White.copy(alpha = 0.8f)
                    ),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
    }
}

@Composable
fun ZoomableEditorCanvas(
    bitmap: Bitmap,
    people: List<DetectedPerson>,
    selectedPersonId: Int?,
    showOverlays: Boolean,
    onPersonTapped: (Int) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.7f, 4.0f)
                    offset = Offset(offset.x + pan.x, offset.y + pan.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    // Find if tap is within any person's bounds
                    // Need coordinate translation from canvas to bitmap space
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            val bmpW = bitmap.width.toFloat()
            val bmpH = bitmap.height.toFloat()

            // Calculate aspect-fit dimensions
            val aspectScale = minOf(canvasW / bmpW, canvasH / bmpH)
            val drawW = bmpW * aspectScale * scale
            val drawH = bmpH * aspectScale * scale

            val drawLeft = (canvasW - drawW) / 2f + offset.x
            val drawTop = (canvasH - drawH) / 2f + offset.y

            // Draw current bitmap
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(drawLeft.roundToInt(), drawTop.roundToInt()),
                dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
            )

            // Draw Overlays (Bounding Boxes, Face Contours, Pose Landmarks)
            if (showOverlays) {
                val scaleX = drawW / bmpW
                val scaleY = drawH / bmpH

                people.forEach { person ->
                    val isSelected = person.id == selectedPersonId
                    val boxColor = if (isSelected) Color(0xFF00EBFF) else Color(0x88FFFFFF)

                    // Bounding Box
                    val bLeft = drawLeft + person.bounds.left * scaleX
                    val bTop = drawTop + person.bounds.top * scaleY
                    val bWidth = person.bounds.width() * scaleX
                    val bHeight = person.bounds.height() * scaleY

                    drawRect(
                        color = boxColor,
                        topLeft = Offset(bLeft, bTop),
                        size = Size(bWidth, bHeight),
                        style = Stroke(width = if (isSelected) 3.dp.toPx() else 1.5.dp.toPx())
                    )

                    // Draw Face Landmarks if present
                    person.face?.let { face ->
                        val fColor = if (isSelected) Color(0xFFFFD166) else Color(0x66FFD166)
                        face.leftEyeCenter?.let { eye ->
                            drawCircle(
                                color = fColor,
                                radius = 4.dp.toPx(),
                                center = Offset(drawLeft + eye.x * scaleX, drawTop + eye.y * scaleY)
                            )
                        }
                        face.rightEyeCenter?.let { eye ->
                            drawCircle(
                                color = fColor,
                                radius = 4.dp.toPx(),
                                center = Offset(drawLeft + eye.x * scaleX, drawTop + eye.y * scaleY)
                            )
                        }
                        face.mouthCenter?.let { mouth ->
                            drawCircle(
                                color = Color(0xFFFF5470),
                                radius = 3.dp.toPx(),
                                center = Offset(drawLeft + mouth.x * scaleX, drawTop + mouth.y * scaleY)
                            )
                        }
                    }

                    // Draw Pose Keypoints if present
                    person.pose?.let { pose ->
                        val pColor = if (isSelected) Color(0xFF2CB67D) else Color(0x662CB67D)
                        listOfNotNull(
                            pose.leftShoulder, pose.rightShoulder,
                            pose.waistCenter, pose.leftHip, pose.rightHip,
                            pose.leftKnee, pose.rightKnee,
                            pose.leftAnkle, pose.rightAnkle
                        ).forEach { pt ->
                            drawCircle(
                                color = pColor,
                                radius = 3.dp.toPx(),
                                center = Offset(drawLeft + pt.x * scaleX, drawTop + pt.y * scaleY)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditorBottomControls(
    selectedTab: EditCategoryTab,
    onTabSelected: (EditCategoryTab) -> Unit,
    currentPerson: DetectedPerson?,
    currentPersonParams: PersonEditParams,
    globalParams: GlobalEditParams,
    isGeminiLoading: Boolean,
    geminiStatus: String?,
    isBackgroundGenerating: Boolean,
    hasReplacedBackground: Boolean,
    backgroundStatus: String?,
    onUpdatePersonParams: ((PersonEditParams) -> PersonEditParams) -> Unit,
    onUpdateGlobalParams: ((GlobalEditParams) -> GlobalEditParams) -> Unit,
    onResetPersonParams: () -> Unit,
    onResetGlobalParams: () -> Unit,
    onResetBokeh: () -> Unit,
    onPickBackgroundFromGallery: () -> Unit,
    onSelectBackgroundPreset: (String) -> Unit,
    onReplaceBackground: (String) -> Unit,
    onResetBackground: () -> Unit,
    onClearBackgroundStatus: () -> Unit,
    onRunGeminiEdit: (String) -> Unit,
    onClearGeminiStatus: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.50f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161622))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Category Tabs
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color(0xFF13131D),
                contentColor = Color.White,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                        color = Color(0xFF00EBFF),
                        height = 3.dp
                    )
                }
            ) {
                EditCategoryTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        text = {
                            Text(
                                text = tab.title,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = tab.icon,
                        selectedContentColor = Color(0xFF00EBFF),
                        unselectedContentColor = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    EditCategoryTab.FACE -> {
                        FaceControlsView(
                            currentPerson = currentPerson,
                            params = currentPersonParams,
                            onUpdate = onUpdatePersonParams,
                            onReset = onResetPersonParams
                        )
                    }
                    EditCategoryTab.BODY -> {
                        BodyControlsView(
                            currentPerson = currentPerson,
                            params = currentPersonParams,
                            onUpdate = onUpdatePersonParams,
                            onReset = onResetPersonParams
                        )
                    }
                    EditCategoryTab.BACKGROUND -> {
                        BackgroundControlsView(
                            params = globalParams,
                            isBackgroundGenerating = isBackgroundGenerating,
                            hasReplacedBackground = hasReplacedBackground,
                            backgroundStatus = backgroundStatus,
                            onUpdate = onUpdateGlobalParams,
                            onResetBokeh = onResetBokeh,
                            onPickGallery = onPickBackgroundFromGallery,
                            onSelectPreset = onSelectBackgroundPreset,
                            onGenerateAiBackground = onReplaceBackground,
                            onResetBackground = onResetBackground,
                            onClearBackgroundStatus = onClearBackgroundStatus
                        )
                    }
                    EditCategoryTab.GLOBAL -> {
                        GlobalControlsView(
                            params = globalParams,
                            onUpdate = onUpdateGlobalParams,
                            onReset = onResetGlobalParams
                        )
                    }
                    EditCategoryTab.AI_MAGIC -> {
                        AiMagicControlsView(
                            isLoading = isGeminiLoading,
                            status = geminiStatus,
                            onApply = onRunGeminiEdit,
                            onClearStatus = onClearGeminiStatus
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FaceControlsView(
    currentPerson: DetectedPerson?,
    params: PersonEditParams,
    onUpdate: ((PersonEditParams) -> PersonEditParams) -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (currentPerson != null) "Ретушь лица (${currentPerson.label})" else "Ретушь лица",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            TextButton(onClick = onReset) {
                Text("Сбросить лицо", fontSize = 12.sp, color = Color(0xFFFFD166))
            }
        }

        // --- Advanced Skin Texture Adjustment Feature ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Текстура кожи и микрорельеф",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00EBFF)
                    )
                    Text(
                        text = "${params.skinTextureIntensity.roundToInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (params.skinTextureIntensity > 0f) Color(0xFF00EBFF) else Color.White.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Mode Selector: Blemish Removal vs Pore Details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = params.skinTextureMode == SkinTextureMode.BLEMISH_REMOVAL,
                        onClick = { onUpdate { it.copy(skinTextureMode = SkinTextureMode.BLEMISH_REMOVAL) } },
                        label = { Text("Удаление дефектов", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF7F5AF0),
                            selectedLabelColor = Color.White,
                            containerColor = Color(0xFF262638),
                            labelColor = Color.White.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = params.skinTextureMode == SkinTextureMode.PORE_DETAILS,
                        onClick = { onUpdate { it.copy(skinTextureMode = SkinTextureMode.PORE_DETAILS) } },
                        label = { Text("Добавление пор", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF7F5AF0),
                            selectedLabelColor = Color.White,
                            containerColor = Color(0xFF262638),
                            labelColor = Color.White.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Mode Explanation Hint
                Text(
                    text = when (params.skinTextureMode) {
                        SkinTextureMode.BLEMISH_REMOVAL -> "Локальное устранение пятен, неровностей и мелких дефектов кожи с сохранением черт лица."
                        SkinTextureMode.PORE_DETAILS -> "Синтез органических микро-пор и контраста для реалистичной текстуры кожи."
                    },
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )

                Slider(
                    value = params.skinTextureIntensity,
                    onValueChange = { v -> onUpdate { it.copy(skinTextureIntensity = v) } },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00EBFF),
                        activeTrackColor = Color(0xFF7F5AF0),
                        inactiveTrackColor = Color(0xFF262638)
                    )
                )
            }
        }

        // 1. Skin Tone & Warmth
        RetouchSlider(
            label = "Тон и яркость кожи",
            value = params.skinToneShift,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(skinToneShift = v) } }
        )

        RetouchSlider(
            label = "Теплота кожи",
            value = params.skinToneWarmth,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(skinToneWarmth = v) } }
        )

        // 2. Smooth Wrinkles & Skin Defect Removal
        RetouchSlider(
            label = "Сглаживание морщин (Bilateral)",
            value = params.skinSmoothing,
            range = 0f..100f,
            onValueChange = { v -> onUpdate { it.copy(skinSmoothing = v) } }
        )

        RetouchSlider(
            label = "Общее сглаживание дефектов",
            value = params.skinDefectRemoval,
            range = 0f..100f,
            onValueChange = { v -> onUpdate { it.copy(skinDefectRemoval = v) } }
        )

        // 3. Eyes Resizing
        RetouchSlider(
            label = "Размер глаз",
            value = params.eyesSize,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(eyesSize = v) } }
        )

        // 4. Lips Volume & Shape
        RetouchSlider(
            label = "Объем губ",
            value = params.lipsVolume,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(lipsVolume = v) } }
        )

        RetouchSlider(
            label = "Форма губ и улыбка",
            value = params.lipsShape,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(lipsShape = v) } }
        )

        // 5. Cheekbones
        RetouchSlider(
            label = "Скульптурирование скул",
            value = params.cheekbones,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(cheekbones = v) } }
        )

        // 6. Chin Reshaping
        RetouchSlider(
            label = "Овал лица и подбородок",
            value = params.chinReshaping,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(chinReshaping = v) } }
        )

        // 7. Hair Color Change
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Цвет волос",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        val hairColors = listOf(
            Pair("Черный", Color(0xFF1E1C1A)),
            Pair("Каштановый", Color(0xFF4A2E1A)),
            Pair("Рыжий", Color(0xFF8B261D)),
            Pair("Платиновый", Color(0xFFE5C07B)),
            Pair("Розовый", Color(0xFFD84A78)),
            Pair("Синий", Color(0xFF2563EB))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            hairColors.forEach { (name, color) ->
                val isSelected = params.hairColorArgb == color.toArgb()
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color, CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color(0xFF00EBFF) else Color.White.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .clickable {
                            onUpdate {
                                it.copy(
                                    hairColorArgb = color.toArgb(),
                                    hairColorIntensity = if (it.hairColorIntensity == 0f) 50f else it.hairColorIntensity
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (params.hairColorArgb != null) {
            RetouchSlider(
                label = "Интенсивность цвета волос",
                value = params.hairColorIntensity,
                range = 0f..100f,
                onValueChange = { v -> onUpdate { it.copy(hairColorIntensity = v) } }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun BodyControlsView(
    currentPerson: DetectedPerson?,
    params: PersonEditParams,
    onUpdate: ((PersonEditParams) -> PersonEditParams) -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (currentPerson != null) "Коррекция фигуры (${currentPerson.label})" else "Коррекция фигуры",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            TextButton(onClick = onReset) {
                Text("Сбросить тело", fontSize = 12.sp, color = Color(0xFFFFD166))
            }
        }

        // 1. Waist Resizing
        RetouchSlider(
            label = "Коррекция талии (сужение / расширение)",
            value = params.waistResizing,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(waistResizing = v) } }
        )

        // 2. Hips Resizing
        RetouchSlider(
            label = "Коррекция бедер (изгиб 'песочные часы')",
            value = params.hipsResizing,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(hipsResizing = v) } }
        )

        // 3. Glutes Resizing
        RetouchSlider(
            label = "Объем ягодиц",
            value = params.glutesResizing,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(glutesResizing = v) } }
        )

        // 4. Ankles Resizing
        RetouchSlider(
            label = "Коррекция щиколоток",
            value = params.anklesResizing,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(anklesResizing = v) } }
        )

        // 5. Height Adjustment
        RetouchSlider(
            label = "Коррекция роста",
            value = params.heightAdjustment,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(heightAdjustment = v) } }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun BackgroundControlsView(
    params: GlobalEditParams,
    isBackgroundGenerating: Boolean,
    hasReplacedBackground: Boolean,
    backgroundStatus: String?,
    onUpdate: ((GlobalEditParams) -> GlobalEditParams) -> Unit,
    onResetBokeh: () -> Unit,
    onPickGallery: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onGenerateAiBackground: (String) -> Unit,
    onResetBackground: () -> Unit,
    onClearBackgroundStatus: () -> Unit
) {
    var bgPromptText by remember { mutableStateOf("") }

    val bgPresets = listOf(
        "Закат на песчаном пляже",
        "Неоновый ночной мегаполис",
        "Уютная европейская кофейня",
        "Роскошный пентхаус с панорамой",
        "Цветущий сад сакуры",
        "Студия с мягким боке"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Настройки фона и боке",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            TextButton(onClick = onResetBokeh) {
                Text("Сбросить боке", fontSize = 12.sp, color = Color(0xFFFFD166))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 1. Portrait bokeh & background lighting card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B2C))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Портретный режим и освещение фона",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Размытие заднего плана с сохранением резкости объектов и коррекция освещения окружения:",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(10.dp))

                RetouchSlider(
                    label = "Размытие фона (Боке)",
                    value = params.backgroundBlur,
                    range = 0f..100f,
                    onValueChange = { v -> onUpdate { it.copy(backgroundBlur = v) } }
                )

                RetouchSlider(
                    label = "Яркость фона (Освещение / Затемнение)",
                    value = params.backgroundBrightness,
                    range = -100f..100f,
                    onValueChange = { v -> onUpdate { it.copy(backgroundBrightness = v) } }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Background replacement & presets
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B2C))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Замена фона",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    if (hasReplacedBackground) {
                        TextButton(
                            onClick = onResetBackground,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Вернуть исходный", fontSize = 12.sp, color = Color(0xFFFFD166))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Используйте собственное фото из галереи или готовый пресет для реалистичной сегментации:",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Gallery button
                Button(
                    onClick = onPickGallery,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Выбрать фото фона из галереи", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Ready Presets
                Text(
                    text = "Готовые локации (мгновенно):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00EBFF)
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bgPresets.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                bgPromptText = preset
                                onSelectPreset(preset)
                            },
                            label = { Text(preset, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFF262638),
                                labelColor = Color.White.copy(alpha = 0.9f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // AI generation
                Text(
                    text = "Или сгенерировать окружение с Gemini ИИ:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7F5AF0)
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = bgPromptText,
                    onValueChange = { bgPromptText = it },
                    placeholder = { Text("Опишите желаемое окружение...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00EBFF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        if (bgPromptText.isNotBlank()) {
                            onGenerateAiBackground(bgPromptText)
                        }
                    },
                    enabled = !isBackgroundGenerating && bgPromptText.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F5AF0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBackgroundGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Создание фона...", fontSize = 12.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Сгенерировать фон через ИИ", fontSize = 12.sp)
                    }
                }

                // Background status message
                if (backgroundStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF26263B))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = backgroundStatus,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onClearBackgroundStatus, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun GlobalControlsView(
    params: GlobalEditParams,
    onUpdate: ((GlobalEditParams) -> GlobalEditParams) -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Общие настройки цвета и света",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            TextButton(onClick = onReset) {
                Text("Сбросить цвет", fontSize = 12.sp, color = Color(0xFFFFD166))
            }
        }

        // Color Grading Presets
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Атмосферная цветокоррекция",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ColorGradePreset.values().forEach { preset ->
                val isSelected = params.colorGrade == preset
                FilterChip(
                    selected = isSelected,
                    onClick = { onUpdate { it.copy(colorGrade = preset) } },
                    label = { Text(preset.displayName, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF7F5AF0),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF262638),
                        labelColor = Color.White.copy(alpha = 0.8f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Sliders
        RetouchSlider(
            label = "Экспозиция / Яркость",
            value = params.exposure,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(exposure = v) } }
        )

        RetouchSlider(
            label = "Контраст",
            value = params.contrast,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(contrast = v) } }
        )

        RetouchSlider(
            label = "Насыщенность",
            value = params.saturation,
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(saturation = v) } }
        )

        RetouchSlider(
            label = "Динамический диапазон",
            value = params.dynamicRange,
            range = 0f..100f,
            onValueChange = { v -> onUpdate { it.copy(dynamicRange = v) } }
        )

        RetouchSlider(
            label = "Шумоподавление",
            value = params.noiseReduction,
            range = 0f..100f,
            onValueChange = { v -> onUpdate { it.copy(noiseReduction = v) } }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AiMagicControlsView(
    isLoading: Boolean,
    status: String?,
    onApply: (String) -> Unit,
    onClearStatus: () -> Unit
) {
    var promptText by remember { mutableStateOf("") }

    val presetPrompts = listOf(
        "Мягкий золотистый свет студии и бархатистая кожа",
        "Шоколадный оттенок волос с сияющими бликами",
        "Очистить фон и создать мягкое размытие боке",
        "Кинематографичный вечерний стиль с легким свечением"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Генеративный ретушер Gemini",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Генеративное редактирование с помощью мультимодальной нейросети Gemini: замена текстур, коррекция освещения и интеллектуальная ретушь.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Quick Preset Prompts
        Text(
            text = "Быстрые сценарии:",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF00EBFF)
        )
        Spacer(modifier = Modifier.height(8.dp))

        presetPrompts.forEach { preset ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        promptText = preset
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222233))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF7F5AF0),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = preset,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Custom Prompt input
        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            placeholder = { Text("Опишите желаемое изменение цвета, текстуры или стиля...", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00EBFF),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (promptText.isNotBlank()) {
                    onApply(promptText)
                }
            },
            enabled = !isLoading && promptText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF7F5AF0)
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Обработка в Gemini...")
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Применить ИИ-ретушь")
            }
        }

        // Status Card
        if (status != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF20202F))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearStatus, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun RetouchSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                text = "${value.roundToInt()}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (value != 0f) Color(0xFF00EBFF) else Color.White.copy(alpha = 0.5f)
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00EBFF),
                activeTrackColor = Color(0xFF7F5AF0),
                inactiveTrackColor = Color(0xFF262638)
            )
        )
    }
}

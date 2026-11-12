package com.spektralab.android.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.android.FilmFilter
import com.spektralab.android.R
import com.spektralab.android.engine.Stock
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.components.BeforeAfterPreview
import com.spektralab.android.ui.theme.TheiaShapes

/** Photo-first workspace: one live preview and a compact horizontal filter strip. */
@Composable
internal fun FilmStockScreen(vm: EditorViewModel, onBack: () -> Unit, startWithPaper: Boolean = false) {
    var paperTab by rememberSaveable { mutableStateOf(startWithPaper) }
    var filter by rememberSaveable { mutableStateOf(FilmFilter.All) }
    var comparing by rememberSaveable(vm.currentUri) { mutableStateOf(false) }
    var creditsVisible by remember { mutableStateOf(false) }
    val enabled = !vm.busy && !vm.isExporting && !vm.isBatchExporting
    val papers = vm.papers.filter { StockCatalog.canPrint(vm.params.filmId, it.id) }
    val films = remember(vm.films, filter) {
        vm.films.filter { stock ->
            when (filter) {
                FilmFilter.All -> true
                FilmFilter.Negative -> stock.type != "positive" && !stock.monochrome && !stock.isCinema()
                FilmFilter.Positive -> stock.type == "positive" && !stock.monochrome
                FilmFilter.Monochrome -> stock.monochrome
                FilmFilter.Cinema -> stock.isCinema()
            }
        }.sortedBy { filmDisplayOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回暗房",
                tint = MaterialTheme.colorScheme.onBackground) }
            Text("胶片与相纸", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground)
            IconButton(onClick = { creditsVisible = true }) { Icon(Icons.Rounded.Info, "图片来源", Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onBackground) }
            TextButton(onClick = onBack, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "应用胶片与相纸" }) {
                Text("完成", fontWeight = FontWeight.SemiBold)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sidePanel = maxWidth >= 700.dp && maxWidth > maxHeight
            if (sidePanel) {
                Row(Modifier.fillMaxSize()) {
                    StockPhotoPreview(vm, comparing, { comparing = it }, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(340.dp).fillMaxHeight().clip(TheiaShapes.panel)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .verticalScroll(rememberScrollState())) {
                        StockFilterPanel(vm, paperTab, { paperTab = it }, filter, { filter = it }, films, papers, enabled)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    StockPhotoPreview(vm, comparing, { comparing = it }, Modifier.weight(1f).fillMaxWidth())
                    StockFilterPanel(vm, paperTab, { paperTab = it }, filter, { filter = it }, films, papers, enabled)
                }
            }
        }
    }
    if (creditsVisible) StockImageCredits(onDismiss = { creditsVisible = false })
}

@Composable
private fun StockPhotoPreview(vm: EditorViewModel, comparing: Boolean, onCompare: (Boolean) -> Unit, modifier: Modifier) {
    val source = vm.sourceBitmap
    val rendered = vm.rendered
    val canCompare = source != null && rendered != null && vm.hasPhoto
    val splitComparison = comparing && canCompare
    val canHold = canCompare && !splitComparison
    var holding by remember(vm.currentUri, source) { mutableStateOf(false) }
    val showingOriginal = canHold && holding
    val photo = if (showingOriginal) source else rendered ?: source
    val previewLabel = stringResource(R.string.stock_photo_preview)
    val originalLabel = stringResource(R.string.stock_original)
    val effectLabel = stringResource(R.string.stock_printed)
    val splitLabel = stringResource(R.string.stock_split_comparison)
    Box(modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics {
        contentDescription = previewLabel
        stateDescription = when {
            splitComparison -> splitLabel
            showingOriginal -> originalLabel
            else -> effectLabel
        }
    }) {
        if (photo != null) {
            // Only the photograph owns the hold gesture; sibling controls keep their clicks.
            Box(Modifier.fillMaxSize().pointerInput(vm.currentUri, source, canHold) {
                if (canHold) detectTapGestures(onPress = {
                    holding = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        holding = false
                    }
                })
            }) {
                if (splitComparison) {
                    BeforeAfterPreview(source!!, rendered!!, Modifier.fillMaxSize())
                } else {
                    Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            IconToggleButton(checked = splitComparison, onCheckedChange = onCompare, enabled = canCompare,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), TheiaShapes.control)) {
                Icon(Icons.Rounded.Compare, stringResource(
                    if (splitComparison) R.string.stock_close_comparison else R.string.stock_compare),
                    tint = if (splitComparison) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp))
            }
            if (canHold) Text(
                stringResource(if (showingOriginal) R.string.stock_original_release else R.string.stock_hold_original),
                Modifier.align(Alignment.BottomStart).padding(10.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .92f), TheiaShapes.badge)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(stringResource(R.string.stock_preview_empty), Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StockFilterPanel(vm: EditorViewModel, paperTab: Boolean, onPaperTab: (Boolean) -> Unit,
    filter: FilmFilter, onFilter: (FilmFilter) -> Unit, films: List<Stock>, papers: List<Stock>, enabled: Boolean) {
    val stocks = if (paperTab) papers else films
    val selectedId = if (paperTab) vm.params.paperId else vm.params.filmId
    val selected = (if (paperTab) vm.papers else vm.films).firstOrNull { it.id == selectedId }
    val filmState = rememberLazyListState()
    val paperState = rememberLazyListState()
    val state = if (paperTab) paperState else filmState
    // Keep the active emulsion visible on entry, after filtering and after a paper auto-pair.
    LaunchedEffect(paperTab, filter, selectedId, stocks.map { it.id }) {
        val index = stocks.indexOfFirst { it.id == selectedId }
        val target = stocks.getOrNull(index.coerceAtLeast(0))
        if (target != null && state.layoutInfo.visibleItemsInfo.none { it.key == target.id }) {
            state.animateScrollToItem(index.coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxWidth().clip(TheiaShapes.sheet).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(bottom = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f), thickness = .5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            listOf(false to "胶片库", true to "相纸").forEach { (paper, label) ->
                Column(Modifier.heightIn(min = 48.dp).clip(TheiaShapes.control).selectable(selected = paperTab == paper, role = Role.Tab,
                    onClick = { onPaperTab(paper) }).padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = if (paperTab == paper) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (paperTab == paper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(if (paperTab) "${papers.size} 款兼容材料" else "${films.size} 款胶片",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!paperTab) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).selectableGroup().semantics { contentDescription = "胶片分类" }) {
            FilmFilter.entries.forEach { option ->
                Box(Modifier.heightIn(min = 48.dp).clip(TheiaShapes.control).selectable(selected = filter == option, role = Role.Tab,
                    onClick = { onFilter(option) }).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(option.label, fontSize = 11.sp,
                        color = if (filter == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (stocks.isEmpty()) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("正在载入材料…", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyRow(state = state, modifier = Modifier.fillMaxWidth().selectableGroup()
                .semantics { contentDescription = if (paperTab) "相纸列表" else "胶片列表" },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stocks, key = { it.id }) { stock ->
                    StockFilterItem(stock, stock.id == selectedId, enabled) {
                        if (paperTab) vm.selectPaper(stock.id) else vm.selectFilm(stock.id)
                    }
                }
            }
        }
        Text(selected?.name ?: "选择材料", Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StockFilterItem(stock: Stock, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val width = 76.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.25f)
    val shape = TheiaShapes.thumbnail
    Column(Modifier.width(width).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = stock.name },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().height(88.dp).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)) {
            StockArtwork(stock.id, Modifier.fillMaxSize())
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.align(Alignment.BottomEnd).padding(3.dp).size(13.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Text(stock.stripLabel(), fontSize = 10.sp, lineHeight = 14.sp, minLines = 2, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Stock.stripLabel(): String = name.removePrefix("Kodak Professional ").removePrefix("Kodak ")
    .removePrefix("Fujifilm ").removePrefix("Ilford ").replace("Professional ", "")
    .replace("Neopan 100 Acros II", "Acros II 100").replace("(Push 1)", "+1").replace("(Push 2)", "+2")

private fun Stock.isCinema() = id.startsWith("kodak_vision3_") || id == "kodak_verita_200d"

private val filmDisplayOrder = listOf(
    "kodak_portra_400", "kodak_gold_200", "fujifilm_pro_400h", "kodak_ektar_100",
    "kodak_portra_160", "kodak_portra_800", "kodak_ultramax_400", "fujifilm_c200", "fujifilm_xtra_400",
    "kodak_portra_800_push1", "kodak_portra_800_push2", "kodak_ektachrome_100", "fujifilm_provia_100f",
    "fujifilm_velvia_100", "kodak_kodachrome_64", "ilford_hp5_plus_400", "kodak_tri_x_400",
    "kodak_tmax_100", "fujifilm_neopan_acros_100_ii", "kodak_vision3_50d", "kodak_vision3_200t",
    "kodak_vision3_250d", "kodak_vision3_500t", "kodak_verita_200d",
)

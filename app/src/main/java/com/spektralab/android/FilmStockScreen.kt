package com.spektralab.android

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.android.engine.Stock
import com.spektralab.android.engine.StockCatalog

private enum class FilmFilter(val label: String) {
    All("全部"), Positive("正片"), Negative("负片"), Monochrome("黑白")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilmStockScreen(vm: EditorViewModel, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FilmFilter.All) }
    val filteredFilms = vm.films.filter { stock ->
        val matchesQuery = query.isBlank() || stock.name.contains(query, ignoreCase = true)
        val matchesType = when (filter) {
            FilmFilter.All -> true
            FilmFilter.Positive -> stock.type == "positive" && !StockCatalog.isMonochrome(stock.id)
            FilmFilter.Negative -> stock.type != "positive" && !StockCatalog.isMonochrome(stock.id)
            FilmFilter.Monochrome -> StockCatalog.isMonochrome(stock.id)
        }
        matchesQuery && matchesType
    }
    val selectedFilm = vm.films.firstOrNull { it.id == vm.params.filmId }
    val currentUri = vm.currentUri
    LaunchedEffect(currentUri) { currentUri?.let(vm::loadThumbnail) }
    val sourceThumb = currentUri?.let { vm.thumbnails[it] }
    val developedThumb = currentUri?.let { vm.developedPreviews[it] }

    BoxWithConstraints(Modifier.fillMaxSize()) {
      val shortLandscape = maxWidth >= 600.dp && maxHeight < 500.dp
      Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("胶片库", style = MaterialTheme.typography.titleMedium)
                        Text("正片 · 负片 · 黑白", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回暗房")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                expandedHeight = 60.dp,
            )
        },
        bottomBar = {
            if (!shortLandscape) Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                FilmApplyActions(vm, onBack, Modifier.navigationBarsPadding().imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        },
    ) { insets ->
        Row(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Column(Modifier.weight(1f).fillMaxHeight(),
              verticalArrangement = Arrangement.spacedBy(if (shortLandscape) 8.dp else 12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("搜索胶片名称") },
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions.Default,
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilmFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            Text("${filteredFilms.size} 款胶片 · 点击实时试片", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (filteredFilms.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的胶片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredFilms, key = { it.id }) { stock ->
                        FilmStockRow(
                            stock = stock,
                            pairedPaperName = stock.targetPrint?.let { target -> vm.papers.firstOrNull { it.id == target }?.name },
                            thumbnail = if (stock.id == vm.params.filmId) developedThumb ?: sourceThumb else sourceThumb,
                            selected = stock.id == vm.params.filmId,
                            onClick = { vm.selectFilm(stock.id) },
                        )
                    }
                }
            }
          }
          if (shortLandscape) {
              Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                  verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  Text("当前胶片", style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(selectedFilm?.name ?: "选择胶片", style = MaterialTheme.typography.titleMedium)
                  FilmApplyActions(vm, onBack)
              }
          }
        }
      }
    }
}

@Composable
private fun FilmApplyActions(vm: EditorViewModel, onApply: () -> Unit, modifier: Modifier = Modifier) {
    val papers = vm.papers.filter { StockCatalog.canPrint(vm.params.filmId, it.id) }
    Column(modifier) {
        StockDropdown("兼容相纸配对", papers, vm.params.paperId, Modifier.fillMaxWidth()) { vm.selectPaper(it.id) }
        Button(
            onClick = onApply,
            enabled = vm.films.any { it.id == vm.params.filmId },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("应用胶片与相纸")
        }
    }
}

@Composable
private fun FilmStockRow(stock: Stock, pairedPaperName: String?, thumbnail: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    val monochrome = StockCatalog.isMonochrome(stock.id)
    val positive = stock.type == "positive" && !monochrome
    val swatch = when {
        monochrome -> Color(0xFF52514D)
        positive -> Color(0xFF7B6748)
        else -> Color(0xFF586147)
    }
    val typeLabel = when {
        monochrome -> "黑白"
        positive -> "正片"
        else -> "负片"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp, 54.dp).clip(RoundedCornerShape(4.dp)).background(swatch),
            contentAlignment = Alignment.Center,
        ) {
          if (thumbnail != null) {
            Image(thumbnail.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
          } else {
            Text(
                when {
                    monochrome -> "B&W"
                    positive -> "REV"
                    else -> "NEG"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF3EEE6),
            )
          }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stock.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$typeLabel  ·  ${pairedPaperName ?: "自动配对"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

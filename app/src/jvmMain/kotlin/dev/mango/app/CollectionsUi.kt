package dev.mango.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mango.core.domain.CollectionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun Modifier.inlineEditKeys(onCommit: () -> Unit, onCancel: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
                onCommit(); true
            }

            Key.Escape -> {
                onCancel(); true
            }

            else -> false
        }
    }

/** Calls onCancel only after the field gained focus and then lost it. */
@Composable
internal fun Modifier.cancelOnFocusLoss(onCancel: () -> Unit): Modifier {
    var hasBeenFocused by remember { mutableStateOf(false) }
    return onFocusChanged { state ->
        if (state.isFocused) {
            hasBeenFocused = true
        } else if (hasBeenFocused) {
            onCancel()
        }
    }
}

data class CollectionCheckboxRow(val id: Long, val name: String, val isDefault: Boolean, val checked: Boolean)

@Composable
private fun CollectionCheckboxItem(row: CollectionCheckboxRow, onToggle: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(
        rest = if (row.checked) theme.accent.copy(alpha = 0.1f) else theme.bg1.copy(alpha = 0f),
        hover = if (row.checked) theme.accent.copy(alpha = 0.1f) else theme.bg1,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardFocusRing(RectangleShape)
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onToggle)
            .padding(horizontal = MangoSpace.sm, vertical = MangoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (row.checked) theme.accent else Color.Transparent)
                .border(1.5.dp, if (row.checked) theme.accent else theme.textTertiary, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (row.checked) {
                Text(text = "✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.accentOn)
            }
        }
        Text(text = row.name, style = MangoType.body, color = theme.textPrimary, modifier = Modifier.weight(1f))
        if (row.isDefault) {
            Text(text = "DEFAULT", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = theme.accent)
        }
    }
}

@Composable
private fun PickerActionRow(text: String, color: Color, onClick: () -> Unit) {
    val theme = LocalMangoTheme.current
    val hover = rememberHoverFill(rest = theme.bg1.copy(alpha = 0f), hover = theme.bg1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardFocusRing(RectangleShape)
            .background(hover.fill)
            .hoverable(hover.interaction)
            .clickable(interactionSource = hover.interaction, indication = null, onClick = onClick)
            .padding(horizontal = MangoSpace.sm, vertical = MangoSpace.sm),
    ) {
        Text(text = text, style = MangoType.body, color = color)
    }
}

/** Toggling checkboxes never leaves the library; only the danger row removes it. */
@Composable
fun AddToCollectionsPicker(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    rows: List<CollectionCheckboxRow>,
    onToggle: (Long) -> Unit,
    onCreateAndFile: suspend (String) -> Unit,
    inLibrary: Boolean,
    onRemoveFromLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMangoTheme.current
    var creating by remember(expanded) { mutableStateOf(false) }
    var draft by remember(expanded) { mutableStateOf("") }
    var error by remember(expanded) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember(expanded) { FocusRequester() }

    fun cancelCreate() {
        creating = false
        draft = ""
        error = null
    }

    fun commitCreate() {
        val trimmed = draft.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            try {
                onCreateAndFile(trimmed)
                creating = false
                draft = ""
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not create collection"
            }
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(MangoRadius.panel),
        containerColor = theme.bg2,
        modifier = modifier.widthIn(min = 220.dp).sideButtons(onDismissRequest),
    ) {
        Text(
            text = "ADD TO",
            style = MangoType.microLabel,
            color = theme.textTertiary,
            modifier = Modifier.padding(horizontal = MangoSpace.sm, vertical = MangoSpace.xs),
        )
        rows.forEach { row -> CollectionCheckboxItem(row = row, onToggle = { onToggle(row.id) }) }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(theme.divider))
        if (creating) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MangoSpace.sm, vertical = MangoSpace.sm),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it; error = null },
                    singleLine = true,
                    textStyle = MangoType.body.copy(color = theme.textPrimary),
                    cursorBrush = SolidColor(theme.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .inlineEditKeys(onCommit = ::commitCreate, onCancel = ::cancelCreate)
                        .cancelOnFocusLoss(::cancelCreate),
                )
                error?.let { message -> Text(text = message, style = MangoType.caption, color = theme.danger) }
            }
        } else {
            PickerActionRow(text = "＋ New collection…", color = theme.textSecondary, onClick = { creating = true })
        }
        if (inLibrary) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(theme.divider))
            PickerActionRow(text = "Remove from library", color = theme.danger, onClick = onRemoveFromLibrary)
        }
    }
}

/** Manage-shelves overlay; duplicate-name rejections from onRename/onCreate render inline. */
@Composable
fun ManageCollectionsDialog(
    collections: List<CollectionInfo>,
    memberCounts: Map<Long, Int>,
    onRename: suspend (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onSetDefault: (Long) -> Unit,
    onCreate: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    var creatingNew by remember { mutableStateOf(false) }
    val ordered = collections.sortedBy { it.position }
    // Holding focus keeps Esc here instead of going back underneath the overlay.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focus)
            .focusTarget()
            .background(theme.bg0.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .shadow(elevation = 32.dp, shape = RoundedCornerShape(MangoRadius.large))
                .clip(RoundedCornerShape(MangoRadius.large))
                .background(theme.bg1)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(MangoSpace.md),
        ) {
            Text(text = "Manage collections", style = MangoType.title, color = theme.textPrimary)
            Spacer(modifier = Modifier.height(MangoSpace.sm))
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                ordered.forEachIndexed { index, collection ->
                    ManageCollectionsRow(
                        collection = collection,
                        count = memberCounts[collection.id] ?: 0,
                        canMoveUp = index > 0,
                        canMoveDown = index < ordered.size - 1,
                        canDelete = ordered.size > 1,
                        onRename = { name -> onRename(collection.id, name) },
                        onDelete = { onDelete(collection.id) },
                        onSetDefault = { onSetDefault(collection.id) },
                        onMoveUp = {
                            val ids = ordered.map { it.id }.toMutableList()
                            val pos = ids.indexOf(collection.id)
                            ids.removeAt(pos)
                            ids.add(pos - 1, collection.id)
                            onReorder(ids)
                        },
                        onMoveDown = {
                            val ids = ordered.map { it.id }.toMutableList()
                            val pos = ids.indexOf(collection.id)
                            ids.removeAt(pos)
                            ids.add(pos + 1, collection.id)
                            onReorder(ids)
                        },
                    )
                }
                if (creatingNew) {
                    PendingCollectionRow(onCreate = onCreate, onClose = { creatingNew = false })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MangoSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "＋ New collection",
                    style = MangoType.body,
                    color = theme.textSecondary,
                    modifier = Modifier.keyboardFocusRing(RoundedCornerShape(MangoRadius.keycap)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { creatingNew = true },
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                KitButton(label = "Done", onClick = onDismiss, style = KitButtonStyle.PRIMARY)
            }
        }
    }
}

@Composable
private fun PendingCollectionRow(onCreate: suspend (String) -> Unit, onClose: () -> Unit) {
    val theme = LocalMangoTheme.current
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun commit() {
        val trimmed = draft.trim()
        if (trimmed.isEmpty()) {
            onClose()
            return
        }
        scope.launch {
            try {
                onCreate(trimmed)
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not create collection"
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = MangoSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
        ) {
            Text(text = "⠿", style = MangoType.body, color = theme.textTertiary.copy(alpha = 0.3f))
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                singleLine = true,
                textStyle = MangoType.body.copy(
                    color = theme.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                ),
                cursorBrush = SolidColor(theme.accent),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .drawBehind {
                        drawLine(
                            color = theme.accent,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                    .inlineEditKeys(onCommit = ::commit, onCancel = onClose)
                    .cancelOnFocusLoss(onClose),
            )
        }
        error?.let { message ->
            Text(
                text = message,
                style = MangoType.caption,
                color = theme.danger,
                modifier = Modifier.padding(start = MangoSpace.md),
            )
        }
    }
}

@Composable
private fun ManageCollectionsRow(
    collection: CollectionInfo,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onRename: suspend (String) -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val theme = LocalMangoTheme.current
    val rowInteraction = remember { MutableInteractionSource() }
    val hovered by rowInteraction.collectIsHoveredAsState()
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(collection.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun commitRename() {
        val trimmed = draft.trim()
        if (trimmed.isEmpty() || trimmed == collection.name) {
            editing = false
            error = null
            return
        }
        scope.launch {
            try {
                onRename(trimmed)
                editing = false
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not rename collection"
            }
        }
    }

    fun cancelRename() {
        editing = false
        draft = collection.name
        error = null
    }

    Column(modifier = Modifier.fillMaxWidth().hoverable(rowInteraction)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = MangoSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MangoSpace.sm),
        ) {
            Text(text = "⠿", style = MangoType.body, color = theme.textTertiary)
            if (editing) {
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it; error = null },
                    singleLine = true,
                    textStyle = MangoType.body.copy(
                        color = theme.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                    ),
                    cursorBrush = SolidColor(theme.accent),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .drawBehind {
                            drawLine(
                                color = theme.accent,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                        .inlineEditKeys(onCommit = ::commitRename, onCancel = ::cancelRename),
                )
            } else {
                Text(
                    text = collection.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(collection.id) {
                            detectTapGestures(onDoubleTap = {
                                draft = collection.name
                                error = null
                                editing = true
                            })
                        },
                )
            }
            Text(text = count.toString(), fontSize = 12.sp, color = theme.textTertiary)
            when {
                collection.isDefault -> Pill(
                    text = "DEFAULT",
                    container = theme.accent.copy(alpha = 0.14f),
                    content = theme.accent,
                )

                hovered -> Text(
                    text = "Make default",
                    style = MangoType.caption,
                    color = theme.accent,
                    modifier = Modifier.keyboardFocusRing(RoundedCornerShape(MangoRadius.keycap)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSetDefault,
                    ),
                )
            }
            if (hovered) {
                Text(
                    text = "▲",
                    style = MangoType.body,
                    color = if (canMoveUp) theme.textSecondary else theme.textTertiary.copy(alpha = 0.3f),
                    modifier = Modifier.keyboardFocusRing(RoundedCornerShape(MangoRadius.keycap)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = canMoveUp,
                        onClick = onMoveUp,
                    ),
                )
                Text(
                    text = "▼",
                    style = MangoType.body,
                    color = if (canMoveDown) theme.textSecondary else theme.textTertiary.copy(alpha = 0.3f),
                    modifier = Modifier.keyboardFocusRing(RoundedCornerShape(MangoRadius.keycap)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = canMoveDown,
                        onClick = onMoveDown,
                    ),
                )
            }
            if (canDelete) {
                Text(
                    text = "✕",
                    style = MangoType.body,
                    color = theme.danger.copy(alpha = 0.8f),
                    modifier = Modifier.keyboardFocusRing(RoundedCornerShape(MangoRadius.keycap)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showDeleteConfirm = true },
                    ),
                )
            }
        }
        error?.let { message ->
            Text(
                text = message,
                style = MangoType.caption,
                color = theme.danger,
                modifier = Modifier.padding(start = MangoSpace.md),
            )
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            modifier = Modifier.sideButtons { showDeleteConfirm = false },
            title = { Text("Delete collection") },
            text = { Text("Delete ${collection.name}? Series stay in All.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

package com.kv4p.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

/** Width kept free on the right of scrollable content for the scrollbar. */
val ScrollbarGutter = 14.dp

/**
 * Easy-to-grab scrollbar style: the uConsole has a trackball but no scroll wheel,
 * so lists must be scrollable by dragging the bar.
 */
val Kv4pScrollbarStyle = ScrollbarStyle(
    minimalHeight = 32.dp,
    thickness = 10.dp,
    shape = RoundedCornerShape(5.dp),
    hoverDurationMillis = 200,
    unhoverColor = Kv4pColors.Primary.copy(alpha = 0.35f),
    hoverColor = Kv4pColors.Primary.copy(alpha = 0.7f),
)

/** [content] with a draggable vertical scrollbar along its right edge. */
@Composable
fun WithScrollbar(state: LazyListState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    WithScrollbar(rememberScrollbarAdapter(state), modifier, content)

@Composable
fun WithScrollbar(state: ScrollState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    WithScrollbar(rememberScrollbarAdapter(state), modifier, content)

@Composable
private fun WithScrollbar(adapter: ScrollbarAdapter, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().padding(end = ScrollbarGutter), content = content)
        VerticalScrollbar(
            adapter = adapter,
            style = Kv4pScrollbarStyle,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * Whether a text field has keyboard focus. While none has, the space bar is PTT even if a
 * button, checkbox or slider was clicked last (those would otherwise take the space key).
 */
object TextFocus {
    var count by mutableStateOf(0)
        private set
    val active: Boolean get() = count > 0

    internal fun change(delta: Int) {
        count = (count + delta).coerceAtLeast(0)
    }
}

/** Put on every text field so the space bar types a space there instead of keying the radio. */
@Composable
fun Modifier.typingField(): Modifier {
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (focused) TextFocus.change(-1) }
    }
    return onFocusChanged { state ->
        if (state.isFocused != focused) {
            focused = state.isFocused
            TextFocus.change(if (focused) 1 else -1)
        }
    }
}

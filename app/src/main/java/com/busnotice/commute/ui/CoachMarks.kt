package com.busnotice.commute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 首页上各聚光目标的实际位置（根布局坐标，只记录完整可见的目标） */
class CoachTargets {
    internal val rects = mutableStateMapOf<CoachStep, Rect>()
    fun available(step: CoachStep): Boolean = rects.containsKey(step)
}

/** 首页通过它登记聚光目标；未提供时 [coachTarget] 不做任何事 */
val LocalCoachTargets = staticCompositionLocalOf<CoachTargets?> { null }

/** 把这个元素登记为 [step] 的聚光目标：只记录位置，不改变布局 */
fun Modifier.coachTarget(step: CoachStep): Modifier = composed {
    val targets = LocalCoachTargets.current ?: return@composed Modifier
    DisposableEffect(targets, step) { onDispose { targets.rects.remove(step) } }
    Modifier.onGloballyPositioned { c ->
        val full = c.size
        val b = if (c.isAttached) c.boundsInRoot() else Rect.Zero
        // 被列表裁掉一部分（滚出屏幕）的目标按不可见处理
        val visible = full.width > 0 && full.height > 0 &&
            b.width >= full.width * 0.9f && b.height >= full.height * 0.9f
        if (visible) {
            if (targets.rects[step] != b) targets.rects[step] = b
        } else {
            targets.rects.remove(step)
        }
    }
}

private class CoachCopy(val title: String, val body: String, val circle: Boolean = false)

private fun copyOf(step: CoachStep) = when (step) {
    CoachStep.NEW_TRIP -> CoachCopy(
        "从这里开始",
        "建一条你的通勤：选好上车站和出门时间就行。查不到的线路，直接写名字也可以。",
    )
    CoachStep.PLACES -> CoachCopy(
        "家和公司不用设",
        "App 会在大约三天里自己认出来。想马上用，在右上角菜单的「设置」里点「我现在在家」。",
    )
    CoachStep.CHECKS -> CoachCopy(
        "提醒准不准，看这里",
        "缺哪些权限和后台设置都列在这，不用一次开完，用到时也会再提醒你。",
    )
    CoachStep.MENU -> CoachCopy(
        "其他都在这",
        "数据源、家和公司、重看这段介绍，都在右上角菜单里。",
        circle = true,
    )
}

/** 等待目标出现的最长时间（首页部分内容异步加载）；超时视为不可见，跳过该步 */
private const val TARGET_WAIT_MS = 2_500L

private val Scrim = Color(0xFF0F0F18).copy(alpha = 0.66f)

/**
 * 首页聚光引导：半透明遮罩挖空目标并描边，旁边放说明气泡。
 * 遮罩拦截所有点击（点遮罩不关闭），只能通过气泡里的按钮或返回键（等同「跳过」）操作。
 */
@Composable
fun CoachMarks(targets: CoachTargets, step: CoachStep, onStep: (CoachStep) -> Unit, onDone: () -> Unit) {
    val toStep by rememberUpdatedState(onStep)
    val done by rememberUpdatedState(onDone)
    val target = targets.rects[step]
    val present = target != null

    // 目标不可见：稍等片刻（首页内容可能还在加载），仍不可见就跳到下一步
    LaunchedEffect(step, present) {
        if (present) return@LaunchedEffect
        val appeared = withTimeoutOrNull(TARGET_WAIT_MS) { snapshotFlow { targets.available(step) }.first { it } }
        if (appeared == null) CoachFlow.next(step)?.let { toStep(it) } ?: done()
    }
    BackHandler { done() }
    if (target == null) return

    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val copy = copyOf(step)
    val available: (CoachStep) -> Boolean = { targets.available(it) }
    val last = CoachFlow.isLast(step, available)
    val (dot, dots) = CoachFlow.dots(step, available)

    var origin by remember { mutableStateOf(Offset.Zero) }
    var bubble by remember { mutableStateOf<Rect?>(null) }
    var below by remember { mutableStateOf(true) }

    val pad = with(density) { 6.dp.toPx() }
    val gap = with(density) { 14.dp.toPx() }
    val arrowW = with(density) { 20.dp.toPx() }
    val arrowH = with(density) { 10.dp.toPx() }
    val stroke = with(density) { 2.5.dp.toPx() }
    val cardCorner = with(density) { 24.dp.toPx() } + pad
    val insetTop = WindowInsets.safeDrawing.getTop(density) + with(density) { 8.dp.toPx() }
    val insetBottom = WindowInsets.safeDrawing.getBottom(density) + with(density) { 8.dp.toPx() }
    val surface = Fuse.surface(dark)

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics { paneTitle = copy.title }
            // 拦截遮罩上的全部触摸，不传给下面的首页
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        val hole = target.translate(-origin).inflate(pad)
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Scrim)
            drawHole(hole, copy.circle, cardCorner, Color.Black, BlendMode.Clear, null)
            drawHole(hole.inflate(stroke / 2), copy.circle, cardCorner + stroke / 2, Fuse.Yellow, BlendMode.SrcOver, Stroke(stroke))
            bubble?.let { b ->
                val x = CoachPlacement.arrowX(hole.center.x, b.left, b.right, with(density) { 28.dp.toPx() })
                val p = Path().apply {
                    if (below) {
                        moveTo(x - arrowW / 2, b.top + 1); lineTo(x + arrowW / 2, b.top + 1); lineTo(x, b.top - arrowH)
                    } else {
                        moveTo(x - arrowW / 2, b.bottom - 1); lineTo(x + arrowW / 2, b.bottom - 1); lineTo(x, b.bottom + arrowH)
                    }
                    close()
                }
                drawPath(p, surface)
            }
        }
        Layout(
            content = {
                CoachBubble(copy, dot, dots, last, dark,
                    onSkip = { done() },
                    onNext = { CoachFlow.firstAvailable(CoachFlow.next(step), available)?.let { toStep(it) } ?: done() })
            },
            modifier = Modifier.fillMaxSize(),
        ) { ms, cs ->
            val margin = 16.dp.roundToPx()
            val w = min(cs.maxWidth - 2 * margin, 400.dp.roundToPx()).coerceAtLeast(0)
            val p = ms[0].measure(Constraints(minWidth = w, maxWidth = w))
            val res = CoachPlacement.place(
                targetTop = hole.top, targetBottom = hole.bottom, bubbleHeight = p.height.toFloat(),
                areaTop = insetTop, areaBottom = cs.maxHeight - insetBottom, gap = gap,
            )
            val x = (cs.maxWidth - w) / 2
            val y = res.y.roundToInt()
            val r = Rect(Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), p.height.toFloat()))
            if (bubble != r) bubble = r
            if (below != res.below) below = res.below
            layout(cs.maxWidth, cs.maxHeight) { p.place(x, y) }
        }
    }
}

private fun DrawScope.drawHole(r: Rect, circle: Boolean, maxCorner: Float, color: Color, mode: BlendMode, style: Stroke?) {
    if (circle) {
        val radius = max(r.width, r.height) / 2
        if (style == null) drawCircle(color, radius, r.center, blendMode = mode)
        else drawCircle(color, radius, r.center, style = style, blendMode = mode)
    } else {
        val c = CornerRadius(min(r.height / 2, maxCorner))
        if (style == null) drawRoundRect(color, r.topLeft, r.size, c, blendMode = mode)
        else drawRoundRect(color, r.topLeft, r.size, c, style = style, blendMode = mode)
    }
}

@Composable
private fun CoachBubble(
    copy: CoachCopy, dot: Int, dots: Int, last: Boolean, dark: Boolean, onSkip: () -> Unit, onNext: () -> Unit,
) {
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Fuse.surface(dark))
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
    ) {
        Text(copy.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink)
        Spacer(Modifier.height(6.dp))
        Text(copy.body, fontSize = 14.sp, lineHeight = 20.sp, color = dim)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(dots) { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(if (i == dot) Fuse.accent(dark) else dim.copy(alpha = 0.3f)))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSkip) { Text("跳过", color = dim) }
            Spacer(Modifier.width(4.dp))
            PillButton(onClick = onNext) { Text(if (last) "开始吧" else "下一步", fontWeight = FontWeight.Bold) }
        }
    }
}

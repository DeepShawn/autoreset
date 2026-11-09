package com.aicode.quotasentry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.quotasentry.core.ProviderId
import com.aicode.quotasentry.core.Store
import com.aicode.quotasentry.ui.components.AuroraBackground
import com.aicode.quotasentry.ui.components.MText
import com.aicode.quotasentry.ui.components.QsIcon
import com.aicode.quotasentry.ui.components.floatingGlass
import com.aicode.quotasentry.ui.screens.AccountSheet
import com.aicode.quotasentry.ui.screens.DashboardScreen
import com.aicode.quotasentry.ui.screens.EventsSheet
import com.aicode.quotasentry.ui.screens.ProviderDetailSheet
import com.aicode.quotasentry.ui.screens.SchedulesScreen
import com.aicode.quotasentry.ui.screens.SettingsScreen
import com.aicode.quotasentry.ui.screens.UpdateDialogHost
import com.aicode.quotasentry.update.UpdateCenter
import com.aicode.quotasentry.ui.screens.rememberNow
import com.aicode.quotasentry.ui.theme.QuotaSentryTheme
import com.aicode.quotasentry.ui.theme.QsDims
import com.aicode.quotasentry.ui.theme.QsTheme
import com.aicode.quotasentry.ui.theme.QsType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 应用根 Composable：单 Activity 三 Tab（首页 / 计划 / 设置）。
 *
 * - HorizontalPager 提供跟手横滑，页面本身按 pageOffset 做轻微缩放/透明（不是 Material 默认 fade）
 * - 底部悬浮玻璃 Tab 栏 + 跟随手指的选中 blob
 * - 卡片 stagger 入场、所有交互 spring
 * - 所有弹层（账号 / 额度详情 / 历史）挂在根上，切页不丢状态
 */
@Composable
fun QuotaSentryRoot() {
    val settings by Store.settings.collectAsState()
    QuotaSentryTheme(settings = settings) {
        RootContent()
    }
}

@Composable
private fun RootContent() {
    val context = LocalContext.current
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var selectedAccount by remember { mutableStateOf<ProviderId?>(null) }
    var selectedProvider by remember { mutableStateOf<ProviderId?>(null) }
    var eventsOpen by remember { mutableStateOf(false) }

    // 冷启动自动检查更新（UpdateCenter 内部限制 6 小时一次，弱网/无网失败静默）。
    LaunchedEffect(Unit) {
        UpdateCenter.maybeAutoCheck(context)
    }

    // 返回键：不在首页时先回上一页，符合单手使用习惯。
    BackHandler(enabled = pager.currentPage != 0) {
        scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 相邻页轻微缩小 + 渐隐，跟手、没有生硬的默认转场。
                        val distance = abs(
                            (pager.currentPage - page) + pager.currentPageOffsetFraction
                        ).coerceIn(0f, 1f)
                        scaleX = 1f - 0.035f * distance
                        scaleY = 1f - 0.035f * distance
                        alpha = 1f - 0.22f * distance
                    }
            ) {
                when (page) {
                    0 -> DashboardScreen(
                        now = now,
                        onOpenAccount = { selectedAccount = it },
                        onOpenEvents = { eventsOpen = true },
                        onOpenProvider = { selectedProvider = it }
                    )

                    1 -> SchedulesScreen(now = now)
                    else -> SettingsScreen(
                        now = now,
                        onOpenAccount = { selectedAccount = it }
                    )
                }
            }
        }

        FloatingTabBar(
            pagerState = pager,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = QsDims.screenPadding, end = QsDims.screenPadding, bottom = 12.dp)
        )
    }

    AccountSheet(
        provider = selectedAccount,
        onDismiss = { selectedAccount = null }
    )
    ProviderDetailSheet(
        provider = selectedProvider,
        onDismiss = { selectedProvider = null },
        onOpenAccount = { selectedAccount = it },
        onOpenEvents = { eventsOpen = true }
    )
    EventsSheet(
        open = eventsOpen,
        onDismiss = { eventsOpen = false }
    )
    UpdateDialogHost()
}

private data class QsTab(val label: String, val icon: QsIcon)

private val tabs = listOf(
    QsTab("首页", QsIcon.HOME),
    QsTab("计划", QsIcon.CALENDAR),
    QsTab("设置", QsIcon.SETTINGS)
)

/** 悬浮玻璃 Tab 栏：选中 blob 直接跟随 pager 偏移（layout 阶段读取，不逐帧重组）。 */
@Composable
private fun FloatingTabBar(pagerState: PagerState, modifier: Modifier = Modifier) {
    val p = QsTheme.palette
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(32.dp)
    val currentPage = pagerState.currentPage

    BoxWithConstraints(
        modifier = modifier
            .height(QsDims.tabBarHeight)
    ) {
        val tabWidth = (maxWidth - 12.dp) / tabs.size.toFloat()
        val tabWidthPx = with(density) { tabWidth.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .floatingGlass(shape)
                .padding(6.dp)
        ) {
            // 选中 blob 跟手滑动，画在玻璃层之上、图标文字之下。
            Box(
                Modifier
                    .offset {
                        val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                            .coerceIn(0f, (tabs.size - 1).toFloat())
                        IntOffset((position * tabWidthPx).roundToInt(), 0)
                    }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(p.accent.copy(alpha = 0.14f))
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == currentPage
                    val tint by animateColorAsState(
                        targetValue = if (selected) p.accent else p.dim,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                        label = "tabTint"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1f else 0.90f,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                        label = "tabScale"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        QsIcon(
                            icon = tab.icon,
                            size = 21.dp,
                            tint = tint,
                            modifier = Modifier.graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                        )
                        Spacer(Modifier.height(3.dp))
                        MText(
                            text = tab.label,
                            color = tint,
                            style = QsType.tiny,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

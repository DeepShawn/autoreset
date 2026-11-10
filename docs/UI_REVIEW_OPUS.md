# 额度哨兵 UI 设计评审（Opus）

> 依据：Theme.kt / Glass.kt / Miuix.kt / 各 Screen / QuotaSentryRoot.kt / Models.kt / QuotaMath.kt 源码，以及 `/root/build/miuix-src` 的 MIUIX 参数。没有真机截图，凡是需要看画面才能判断的地方都标了【需真机确认】。

## 1. 总评

**一句话**：组件骨架和动效基础是对的（只动 graphicsLayer、没有 blur、到处用 spring），但现在更像“深色半透明卡片堆”，还不像 Liquid Glass。首页同一件事反复说好几遍；浅色主题对比度不达标；Sheet 不能跟手拖；Switch 有绘制 bug。

| 维度 | 分数 | 主要扣分点 |
|---|---|---|
| 视觉 | 6/10 | 高光铺满整张卡，玻璃边缘不立体；首页没有视觉焦点；有三个强调色主按钮同时抢眼 |
| 交互 | 5.5/10 | Sheet 不能拖动关闭；Tab 选中色在半程突然切换；Slider 一按就改值，滚动时容易误改 |
| 性能 | 6/10 | 光斑无限动画，导致全窗口每帧重绘；Pager 的 alpha 会触发离屏绘制；历史 Sheet 一次组合 80 行 |
| 一致性 | 6/10 | 同一类元素出现 4 种圆角、5 种阴影；ring、进度条、文字三处“低额度”的颜色阈值不一致 |

---

## 2. Top 8 必须改

### ① MiuixSwitch 轨道画成了圆（S）
- **问题**：`Miuix.kt` 里 `drawBehind { drawCircle(trackColor, radius = size.minDimension/2) }` 是在 49×28dp 的盒子中间画一个 28dp 的圆，左右各有 10.5dp 没有底色。关闭状态下滑块（offset 4dp）会落在色块外面。这一点从代码就能确定，不是猜测。
- **改法**：改成 `drawRoundRect(trackColor, cornerRadius = CornerRadius(size.height/2))`。顺便补上 MIUIX 的拖动：给滑块加 `draggable`，`dragOffset` 限制在 0..21dp，松手时根据是否过半来决定开或关。震动从 `LongPress` 改成 `HapticFeedbackType.ToggleOn/ToggleOff`，并在 `onCheckedChange` 之前触发。

### ② 浅色主题对比度不合格（S）
实测（WCAG 对比度，背景为 `#EEF2F7`）：
- 浅色 `onAccent = 白`：白字压在薄荷色上只有 **1.56**，琥珀色上 1.57，天青 2.11。所以浅色下的主按钮、分段选中、选中的 chip 文字基本看不清。
- `accent` 直接当文字或图标用（例如 `RowLabel valueColor = p.accent`、Tab、ListRow 的图标）：薄荷色在浅底上只有 **1.39**。
- `faint #8593A6` 在浅色下 2.78，在深色 `#07090F` 上 3.63，压在卡片上只有 2.87，却被用在“上次发送”、计划说明这类有用信息上。
- **改法**：新增 `accentInk`（浅色下给文字和图标用的深色强调色，见 §4），`onAccent` 两套主题统一用 `#06110E`（5 种强调色上对比度都在 7 以上）；`faint` 深色改成 `#748399`，浅色改成 `#66758A`，并规定 faint 只用于 12sp 以上的非关键文字。浅色下 `strokeTop` 是白 92%，压在白卡上看不见，需要改成“白色顶边 + `#101722` 10% 底边”的双色描边。
- 文件：`Theme.kt` 的 `buildPalette`，再把 `p.accent` 当文字用的地方全局换成 `p.accentInk`。

### ③ GlassSheet 不跟手（M）
- **问题**：
  - 有拖拽提示条，但实际不能拖；只能点遮罩或 ✕ 关闭。
  - 位移写死为 `(1-progress)*1200.dp`：Sheet 实际约 500dp 高，所以入场前段都在屏幕外“空跑”，看起来像延迟；退场是 190ms tween 走完 1200dp，可见部分约 60ms 就闪没了。
  - 详情 Sheet 里点“编辑 Cookie”或“历史记录”会再叠一个 Dialog，两层遮罩叠起来约 77% 黑。
  - Compose Dialog 窗口默认可能自带 dim 和窗口动画，和我们自己的 scrim 叠加【需真机确认】。
- **改法**：见 §6 任务 B。

### ④ 首页信息重复、没有焦点（L）
同一件“后台没运行”出现了 5 次：顶部副标题、StatusPill、概览卡里的提示行、每张 Provider 卡上的“服务未运行”chip、详情 Sheet 的 chip。额度环和下方细进度条显示的是**同一个数**（`remainingFraction`）。三张卡上各有一个带 14dp 彩色阴影的“立即续命”主按钮。具体改法见 §3.1 和 §6 任务 C。

### ⑤ 玻璃材质：高光要收在边缘（M）
- **问题**：
  - `glassSurface` 的竖向高光按比例铺到卡片高度的 62%，再叠一层从左上铺到整卡的斜向反光。卡越高，雾越大，文字对比度被整体拉低。真正的 Liquid Glass 是**中间通透、边缘发亮**。
  - 描边只有左上到右下的单向渐变，看不出厚度。
  - 卡片里的 chip、次按钮、图标按钮又各自套了一层 `glassSurface`（自带阴影和高光），形成玻璃叠玻璃，违反“玻璃层级 ≤2”。
- **改法**：
  1. 高光改成**固定 20dp 的顶部高光带**（`endY = 20.dp.toPx()`），删掉整卡斜向反光。
  2. 描边改成“双亮角”轮廓光：`linearGradient(0f 白34%, 0.3f 白6%, 0.7f 透明, 1f 白12%)`，左上主光、右下回光。再画一圈向内缩 1dp、黑 18%（浅色下 `#101722` 5%）的内描边，用来表现厚度和边缘折射。
  3. 阴影从 `Modifier.shadow(12dp)` 换成 Compose 1.9+ 的 `Modifier.dropShadow(shape, Shadow(radius=28.dp, offset=DpOffset(0,10.dp), spread=(-6).dp, alpha=…))`，参数名以 IDE 为准。负 spread 加向下偏移，阴影边缘更干净。半透明卡片底下的阴影会透出来变灰，这一点【需真机确认】，透出明显就把浅色主题的阴影 alpha 降到 0.10。
  4. 卡片内部的元素一律改用新增的 `Modifier.tonal()`：填充 `p.text` 7%，无描边、无阴影。
  5. 所有 `drawBehind` 改成 `drawWithCache`，避免每次重绘都新建 Brush。
- 文件：`Glass.kt` 的 `glassSurface/GlassButton(SECONDARY)/GlassChip/GlassIconButton/StatusPill`。

### ⑥ Tab 栏和 Pager 要做成“液体”（M）
- **问题**：
  - blob 位置跟手，但图标颜色由 `currentPage` 决定，滑过一半时颜色突变。
  - blob 只是一块 14% 的强调色，没有拉伸、按压和玻璃感。
  - Pager 邻页加了 `alpha=0.78`，滑动时两张全屏页都要走离屏缓冲。
  - 吸附用的是默认 spring，`animateScrollToPage` 也没有指定 spring 参数。
  - 底部留白 `contentBottom=128dp` 没算导航栏 inset，三键导航时最后一张卡会被挡住。
  - 状态栏下面没有遮罩，内容滚上去会和状态栏图标重叠。
- **改法**：见 §6 任务 A。

### ⑦ 中文排版（S）
- **问题**：
  - `QsType` 全部没有 lineHeight。
  - 32sp 和 28sp 的中文标题用了 `letterSpacing -0.6/-0.4sp`。负字距是给拉丁字母用的，用在中文上字会挤在一起。
  - 11sp（tiny）承载了大量正文，比如“本窗口已发”、错误信息、步骤说明；10sp 的 `ringUnit` 更是太小。
  - 倒计时和百分比没用等宽数字，30 秒刷新一次时会左右抖。
- **改法**：按 §4 的字级表替换。中文样式字距统一为 0；数字样式加 `fontFeatureSettings = "tnum"`（MiSans 是否支持 tnum【需真机确认】，不支持就给数字套一个固定宽度的 Box）；所有样式加 `lineHeightStyle = LineHeightStyle(Center, Trim.None)`。

### ⑧ 误触和误操作（S–M）
- `MiuixSlider` 按下就跳到手指位置并触发 `emit`。设置页有 4 个滑块，从滑块上开始竖向滚动列表就会改掉条数上限，松手时还会 `onCommit` 写进存储。**改法**：按下只记录位置；横向移动超过 slop 且 |dx|>|dy| 才进入拖动；没有移动就抬手才算点击定位；竖向超过 slop 就放弃，不改值。
- `StaggerItem` 用的是 `remember + LaunchedEffect(Unit)`。LazyColumn 里的项滚出屏幕再回来会重播入场动画（最多延迟 300ms 才出现）。**改法**：在每个页面里提升一个 `val played = remember { mutableSetOf<Any>() }`，已经播过的 key 直接把 t 设为 1；index 上限 5。
- 下面这些危险操作没有二次确认：点 StatusPill 直接**停止守护**；AccountSheet 里的“断开”会直接清空凭据；“重置额度窗口”；“删除这个计划”。**改法**：StatusPill 只在“未启动”状态下点击开启，停止守护只放在设置页的开关里；其余三项统一走 `ConfirmSheet`。

---

## 3. 分页面逐条

### 3.1 首页 `DashboardScreen.kt`
1. **Header**：保留 `额度哨兵`（28sp SemiBold）和两个图标按钮。副标题删掉“状态胶囊可开启或暂停守护”这类操作说明文字。StatusPill 合并进概览卡。顶部总高度从约 102dp 降到约 64dp。
2. **OverviewCard 做成 Hero**：
   - 第一行：守护状态（呼吸点 + “守护中 · 3 小时 12 分”）。
   - 主体：**下一次续命时间**，34sp、tnum，例如 `09:12`；旁边 15sp dim 写“Claude · 4 小时后”。
   - 底部：三家状态点。
   - 没在运行时整张卡切成 warn 色调，只保留**一个**“开启守护”按钮（44dp tonal）。
   - Provider 卡上的“服务未运行”chip 全部删掉。
3. **ProviderCard 额度环**：
   - 中心统一显示**百分比**（三家才能横向比较），下面 12sp 小字写“官方”或“约 41/45 条”。
   - 用线型区分来源：官方 = 实线；估算 = `PathEffect.dashPathEffect(floatArrayOf(2dp, 3dp))` 虚线；待刷新 = 虚线 + warn 色。
   - 渐变从 `linearGradient`（终点颜色总落在右下角）改成 `Brush.sweepGradient`，旋转 -90°，色标 0..animated，保证端点亮珠和弧尾颜色一致。
4. **细进度条换语义**：改为显示“窗口已过去的时间”，即 `1 - (resetAt-now)/windowMillis`，右侧写 `2:13 后重置`（tnum）。估算且本窗口还没发送（`resetAt == null`）时显示“窗口未开始 · 发送后计时”，不要显示“重置时间未知”，那像是出错了。
5. **阈值统一**：新增 `enum QuotaLevel { OK, WARN, LOW }`，由 `QuotaView` 统一算出一次（LOW = `view.low`，WARN = 剩余 ≤35%）。ring、进度条、文字、卡片 glow 都只读这个值。现在 `QuotaRing(low)`、`quotaBrush(fraction)`、`quotaTone` 三处阈值各不相同。
6. **操作行**：44dp 主按钮 + 次按钮 + 40dp 更多 → 改成 40dp 的 tonal“续命”和 40dp 的图标按钮“⋯”（hit 区 48dp）。主按钮渐变**只在 LOW 或本窗口还没发送时**出现。卡片上的“刷新”删掉（Header 已经有全局刷新）。
7. **文字**：`quotaSummary` 现在把三段拼成一行（13sp，最多 2 行），拆成左右两段对齐。`本窗口已发` 和 `上次发送` 从 11sp 升到 12sp，faint 改 dim。
8. **时间线**：保持现状；EventRow 主文 13sp、详情 12sp。
9. **历史 Sheet**：80 行放在 `verticalScroll` 的 Column 里，打开时一次全部组合，低端机首帧会卡。改成 `LazyColumn(Modifier.heightIn(max = …))`，并把 `rememberNow()` 改为复用根节点传下来的 `now`（现在一共 3 个计时器）。

### 3.2 计划页 `SchedulesScreen.kt`
1. **RuleCard 信息重复三遍**：“同步额度”同时出现在 RowLabel 和 chip 里；“编辑”chip 和点卡片是同一个动作；“排程 HH:mm”和“下次 …”说的是同一件事。而且“同步额度已开”这个 chip 不能点，却长得像按钮。**改为**：
   - 头部：logo、名称、开关；
   - 主体：**下次时间 28sp tnum** + “4 小时后 · 跟随窗口”；
   - 底部一行 12sp：“消息 Hi · 上次 10 分钟前成功”。
   - chip 行整行删掉。
2. **暂停的计划**：整卡内容 `alpha 0.55`，开关保持 1.0。
3. **新增入口**：在 `QsTopBar` 的 actions 放一个“＋”图标按钮（44dp）。底部的全宽按钮只在列表少于 3 条时显示，否则滚出屏幕就找不到了。
4. **每天定时**：两个 Stepper 从 09 调到 21 要点 12 下。改成滚轮：`LazyColumn` + `rememberSnapFlingBehavior(lazyListState)`，行高 40dp、显示 3 行；选中项 20sp text 色，其他项在 graphicsLayer 里 `scale 0.82 / alpha 0.4`，按 `layoutInfo` 和中心的距离插值。时和分并排放。
5. **固定间隔**：滑块上方加快捷 chip：`2 小时 / 4.5 小时 / 5 小时`。
6. 页面底部的 4 行 faint 11sp 说明删掉：模式说明已经在编辑 Sheet 里有（`mode.hint`）。

### 3.3 设置页 `SettingsScreen.kt`
1. **ListRow 向 MIUIX 靠拢**：标题 14→**16sp Medium**，副标题 12→**13sp**，与 MIUIX BasicComponent 的 17/14 对齐（略缩一号以兼顾密度）。按压底色 4.5% → **8%**；进入 `spring(1f, 987f)`、退出 `spring(0.95f, 322f)`（即 MIUIX folme 的 response 0.2/0.35）。
2. **带开关的行整行可点**：现在只有 49×28 的开关能点，改成点整行就切换开关（MIUIX SuperSwitch 的行为）。
3. **StatusRow 有 bug**：
   - `backgroundEnabled=true` 但服务没在运行时，`actionText=null`，右侧会显示绿色的“已开启”，和副标题“未运行”自相矛盾。
   - 状态不正常时的 WARN 图标用的是 accent 色（`ListRow` 的 leadingIcon 固定用 accent 着色）。
   - **改法**：右侧按 `ok` 显示 ✓ 或“去设置”；leading 图标的着色跟随 ok/warn。
4. **SegmentedControl**：现在主题、刷新间隔、代理类型、服务选择、模式这五处的选中块都是强调色渐变，一屏五块薄荷色。改成 HyperOS 风格：选中块用中性玻璃（深色白 14% / 浅色纯白）+ 2dp 阴影，文字用 `p.text` SemiBold，强调色只留给主按钮。圆角 15/12 → 14/10（MIUIX TabRow 是 12）。指示条的 `offset(x = Dp)` 是在组合阶段读取的，每帧都会重组，改成 `offset { IntOffset(...) }`。
5. **Slider 换成 MIUIX 粗轨**：轨道 6dp 加 20dp 白球 → **28dp 粗胶囊轨**（`SliderDefaults.MinHeight = 28.dp`），填充用强调色，不要单独的滑块球；数值写在行右侧。拖动跟手用 `spring(0.9f, 1755f)`，松手回弹用 `spring(0.96f, 322f)`（MIUIX 原值）。
6. **Stepper**：StepButton 视觉 34dp → 36dp，点击区做到 48dp。
7. **代理输入**：MainActivity 是 edge-to-edge + `adjustResize`，在 Android 11 以上不会真的缩小窗口，代理输入框可能被键盘盖住【需真机确认】。给页面 LazyColumn 加 `Modifier.imePadding()`；键盘弹出时隐藏 Tab 栏（`WindowInsets.ime.getBottom(density) > 0`）。
8. **GlassTextField**：
   - placeholder 放在外层 Box，宽度不受 trailing 的“显示”chip 限制，长占位文字会压到 chip 下面，这是 bug。把 placeholder 移进 `Box(Modifier.weight(1f))`。
   - 多行时 trailing 应该顶部对齐。
   - 补上聚焦态：`onFocusChanged` 时把 1.5dp accent 描边做 spring 动画（MIUIX 是 2dp）。
   - 圆角 14 → 16dp。

### 3.4 账号 Sheet `AccountSheet.kt`
1. **一屏 9 个区块**（状态、验证时间、错误卡、反馈卡、Cookie 输入、两个 chip、OAuth 面板、Token 区、按钮、帮助卡、提示），信息过载。**改为方式优先**：顶部分段 `Chrome 授权 | 粘贴 Cookie`（Gemini 只有后者），一次只显示一种方式的内容。
2. **Chrome 授权**：未开始时放一个 52dp 的主按钮“用 Chrome 授权登录”，下面一行 12sp 说明。现在的入口是一个 34dp chip，面板里只有说明文字、没有按钮，两处重复。
3. **Cookie 方式**：输入框 + “粘贴并测试”（主按钮，把粘贴和测试合成一步）。“保存”按钮删掉（关闭 Sheet 时已经会 `persist`）。“需要哪些 Cookie”默认折叠，点击后用 `animateContentSize(spring(0.9f, 500f))` 展开。
4. 错误卡和反馈卡合并成一个结果条：成功显示 ok 色，失败显示 danger 色，并带“重试”。
5. “断开”chip 移到底部，做成 DANGER 文字按钮，走 ConfirmSheet。

### 3.5 Tab 栏 `QuotaSentryRoot.kt`
见任务 A。另外：标签 11sp → 12sp（MIUIX `LabelFontSize = 12.sp`），图标 21 → 24dp。整栏可以改成居中的胶囊（宽约 3×88+12dp），不再撑满屏幕，更有悬浮感【需真机确认观感】。

### 3.6 弹窗 `UpdateDialog.kt` / `ConfirmSheet` / `InfoSheet`
- 更新弹窗的“下载并安装”主按钮 52dp，下面又有两个 46dp 按钮，三个按钮层级太平。改成“Release 页面”文字链接 +“稍后”次按钮。下载中的按钮写着“正在下载…”却是禁用的主按钮，直接去掉，进度条本身就是状态。
- ConfirmSheet 用的是可拖动的大 Sheet，太重。危险操作改成**居中的玻璃对话框**：宽 `min(屏宽-48dp, 360dp)`，圆角 28，入场 `scale 0.92→1 + alpha` 用 `spring(0.8f, 500f)`。
- 所有 Sheet 关闭按钮的视觉 36dp 可以不变，但点击区要做到 48dp。

---

## 4. 建议的新设计 token（直接替换 `Theme.kt`）

```kotlin
// ── 颜色：深色 ──
faint        = Color(0xFF748399)      // 5.2:1 on bg, 4.1:1 on card
divider      = White.copy(.08f)
glassTop     = White.copy(.09f*i) over accent.copy(.04f*i)
glassBottom  = White.copy(.035f*i)
rimTop       = White.copy(.34f*i)    // 左上主光
rimBottom    = White.copy(.12f*i)    // 右下回光
innerShade   = Black.copy(.18f)      // 内缩 1dp 内描边
highlight    = White.copy(.14f*i)    // 仅顶部 20dp 带
onAccent     = Color(0xFF06110E)     // 两套主题一致
accentInk    = accent                // 深色直接用 accent
// ── 颜色：浅色 ──
bgTop/bgBottom = #F3F6FA / #E8EDF4
dim   #5C6B80（保留，4.8:1）  faint #66758A
ok    #07735A  warn #955800  danger #C42F45   // 均 ≥4.8:1
glassTop White(.72f*i)  glassBottom White(.52f*i)
rimTop White(1f)  rimBottom Color(0xFF101722).copy(.10f)
innerShade Color(0xFF101722).copy(.05f)
accentInk: 薄荷 #07735A 紫罗兰 #5B47D6 天青 #1866C4
           玫瑰 #B8325A 琥珀 #8E5600     // 全部 ≥5:1
secondAccent: 改为 HSV 色相 +28°、饱和度 ×0.9，
             不要 lerp 到 #7FB4FF（琥珀、玫瑰会混出发灰的中间色）

// ── 圆角 ──
hero 28 / card 24 / section 24 / inner 16 / input 16
segmented 14(外)/10(内) / sheet 32 / dialog 28 / chip&button = 高/2
// 可选：G2 连续曲率角（HyperOS 的 squircle 观感）。
// 每个角用一段 cubic：P0 = 1.5r，控制点 0.27r，曲线延伸长度 ≤ min(w,h)/2【需真机对比】

// ── 间距 ──
screen 20 / 卡片内边距 20 / 卡片间距 14 / 分区之间 24（含小标题）
卡片内部按 4 / 8 / 12 / 16 网格

// ── 字级（中文 letterSpacing 一律 0）──
display     34sp SemiBold lh40 tnum   // Hero 时间、额度环数值
largeTitle  28sp SemiBold lh34
title       20sp SemiBold lh26
headline    17sp Medium   lh22        // 卡片标题
rowTitle    16sp Medium   lh21
body        15sp          lh22
label       14sp          lh20
caption     13sp          lh18
footnote    12sp          lh16        // 正文最小字号
micro       11sp Medium   lh14        // 只用于徽标
ringValue   22sp SemiBold tnum ls -0.3sp

// ── 阴影 ──
card     dropShadow r28 y10 spread-6  α .35(深)/.10(浅)
floating dropShadow r32 y12 spread-4  α .45/.14   // Tab 栏
inner    无阴影（chip/次按钮/图标按钮/分段）

// ── Spring ──
press 进入 spring(1f, 987f)；退出 spring(0.95f, 322f)
  缩放：卡片 .97 / 按钮 .96 / chip .94 / 图标 .90
pagerSnap / tabTap  spring(0.86f, 380f)
blob 拉伸           跟随 pager 位置，在绘制阶段计算，不另设 spring
sheet 打开          spring(0.86f, 420f)
sheet 松手回弹      spring(0.9f, 500f, initialVelocity=v)
sheet 关闭          spring(1f, 600f, initialVelocity=v)
dialog              spring(0.8f, 500f)
switch 滑块 (0.7f, 987f)  颜色 (0.99f, 438.6f)   // 照 MIUIX
slider 拖动 (0.9f, 1755f)  回弹 (0.96f, 322f)
segmented 指示条 spring(0.78f, 500f)
stagger  spring(0.82f, 380f)，每项延迟 40ms，index 上限 5，只在首次进入时播
```

---

## 5. 不建议做的

1. **不要做“伪背景采样”**：比如每张卡用 `onGloballyPositioned` 拿到屏幕位置，再按光斑颜色给卡片染色。滚动时每帧都要写状态、触发重绘，代价大于收益。背景本来就是低频的柔光，半透明填充已经自然透出颜色。
2. **不要上 RenderEffect、AGSL 折射或 `Modifier.blur`**（包括只给卡片用）：API 31/33 以上才有，会造成机型间效果割裂；背景没有高频细节，模糊了也看不出区别。
3. **光斑不要加数量、不要提速、不要改成 Canvas 逐帧重绘**。正确做法：给光斑层加 `compositingStrategy = CompositingStrategy.Offscreen`，只光栅化一次；把 `infiniteTransition` 换成 `delay(66)` 驱动的约 15fps 循环（每秒才移动 0.76dp，肉眼看不出区别）；系统省电模式（`PowerManager.isPowerSaveMode`）或 `glassIntensity < 0.6` 时直接静止。现在的写法会让整个窗口以屏幕刷新率持续重绘，在 120Hz 机器上尤其费电【需真机看 GPU 呈现模式】。
4. **拖动玻璃强度滑块时不要实时换主题**：palette 一变整棵树都要重组。保持现在的“松手才提交”。
5. **不要做 card→sheet 的共享元素过渡**：Sheet 是独立的 Dialog 窗口，跨窗口没法共享元素。
6. **倒计时不要做数字滚动动画**：数值 30 秒才变一次，滚动动画只会分散注意力。
7. **不要再加玻璃层**：卡片里面一律用 tonal 填充；Sheet 里的信息块用 `inner 16` 圆角加 tonal，不要再套 `GlassCard`。
8. **不要用陀螺仪做高光视差**：费电，而且会和“简单页面”的诉求冲突。

---

## 6. 最值得做的 3 个改动（可直接派单）

### 任务 A：液体 Tab 栏 + Pager 物理（`QuotaSentryRoot.kt`，M）
1. **Pager**：
   - 去掉页面变换里的 `alpha`，只保留 `scale = 1 - 0.04*distance`。
   - `flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(0.86f, 380f), snapPositionalThreshold = 0.35f)`。
   - Tab 点击：`animateScrollToPage(i, animationSpec = spring(0.86f, 380f))`。
2. **选中色连续过渡**：图标和文字画两层，下层是 dim 色的一排，上层是 accentInk 色的一排。上层用 `Modifier.drawWithContent { clipRect(blobL, 0f, blobR, h) { drawContent() } }` 裁到 blob 范围内，blob 的位置在**绘制阶段**读 `pager.currentPage + currentPageOffsetFraction`。这样颜色像透镜一样跟着 blob 走，不会在半程突变，而且没有重组。删掉 `animateColorAsState`。
3. **blob 本体**：
   - 填充：白 12%（浅色为白 90%），加 1dp rimTop 描边。
   - 拉伸：`frac = position % 1`，宽度乘以 `1 + 0.22*sin(π*frac)`，高度乘以 `1 - 0.06*sin(π*frac)`。
   - 按下 Tab 栏时 blob 放大到 `scale 1.06`，用 `spring(0.6f, 700f)`。
4. **拖动切页**：在 Tab 栏上加横向 `draggable`，用 `pager.dispatchRawDelta(-dx * pageWidth / tabWidth)` 让页面跟手；松手按速度吸附到最近的页。
5. **边界**：
   - 页面底部留白 = `64 + 12 + 16dp + navigationBars.bottom`。
   - 根节点加顶部遮罩：高度为状态栏高度 + 24dp，`verticalGradient(bgTop 92% → 透明)`。
   - Tab 栏后面加一条 40dp 的底部渐隐。
6. **验收**：滑到一半时，blob 下面的图标一半是强调色、一半是 dim 色；在“GPU 呈现模式”里滑动时不出现超过 16ms 的柱。

### 任务 B：可拖拽的 GlassSheet（`Miuix.kt` 的 `GlassSheet`，M）
1. 用 `onSizeChanged` 记录 Sheet 高度 `hPx`，位移改为 `translationY = (1 - progress) * hPx + dragOffset`。
2. 拖拽提示条和标题区加 `draggable(Vertical)`。内容区用 `NestedScrollConnection`：内容已经滚到顶、还在往下拉时，把位移交给 Sheet；往上拉超过顶部时按 0.3 的阻尼处理。
3. **松手**：`dragOffset > 0.25*hPx` 或速度 > 1200dp/s → 用 `spring(1f, 600f, initialVelocity = v)` 动画到 hPx，然后调 `onDismiss`；否则用 `spring(0.9f, 500f)` 回到 0。遮罩透明度跟随 `1 - offset/hPx`。
4. **Dialog 窗口**：通过 `(LocalView.current.parent as DialogWindowProvider).window` 调 `setDimAmount(0f)`、`setWindowAnimations(0)`，并设置 `isAppearanceLightNavigationBars`【需真机确认原本是否有双重遮罩】。
5. **禁止 Sheet 叠 Sheet**：ProviderDetailSheet 里点“编辑 Cookie / 历史记录”时，先关闭当前 Sheet，等动画结束（`progress == 0` 回调）再打开下一个。
6. 关闭按钮的点击区做到 48dp。
7. **验收**：
   - 手指按住 Sheet 往下拖，Sheet 逐帧跟随；
   - 轻甩能关闭；
   - 慢拖不到四分之一时松手会回弹；
   - Cookie 输入框获得焦点时，Sheet 跟着键盘上移，输入框可见。

### 任务 C：首页信息重构 + 统一额度可视化（`DashboardScreen.kt`、`Glass.kt`、`Theme.kt`，L）
1. **Header**：只保留标题和两个图标按钮；StatusPill 并入概览卡。点击 pill 只负责“开启”，不再负责停止。
2. **概览卡改为 Hero**：
   - 主体是下一次续命时间（display 34sp、tnum）+ 对应 Provider 和相对时间；
   - 下方三家状态点；
   - 服务没运行时整卡切到 warn 色调，只保留一个“开启守护”按钮。
   - 删掉所有 Provider 卡上的“服务未运行”chip。
3. **QuotaRing**：
   - 中心统一显示百分比，下方小字“官方”或“约 n/N 条”；
   - 官方 = 实线，估算 = 虚线，待刷新 = 虚线 + warn；
   - 改用 `sweepGradient`；
   - 颜色由 `QuotaLevel` 统一决定（新增在 `QuotaView` 的 UI 扩展里，不修改 core 的数据结构）。
4. **细进度条改为窗口时间进度**，右侧写 `h:mm 后重置`；本窗口还没开始时显示“窗口未开始”。
5. **操作行**：“续命”是 40dp tonal 按钮，只在 LOW 或本窗口未发送时才变成主按钮渐变；卡片上的“刷新”删除；“⋯”的点击区 48dp。
6. **验收**：
   - 三家都已连接、守护正常时，首屏只有**一个**强调色实心元素（Hero 时间或某张 LOW 卡的按钮）；
   - 三个额度环的单位一致；
   - “后台没运行”只出现一次。

> **建议的执行顺序**：先把 S 级的 ①②⑦⑧ 一次改完（约半天），再做 A → B → C。

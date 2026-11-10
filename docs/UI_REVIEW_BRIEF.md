# UI 设计评审任务书（交给 Claude Opus 5.5）

## 你要做什么

这是一个已经能跑的 Android App（Kotlin + Jetpack Compose，无 Material3、无图标库、无第三方 UI 库），
主题是「Liquid Glass + MIUIX 风格」。请你**从资深移动端 UI/UX 设计师 + Compose 实现专家**的角度，
对现有界面做一次严格评审，输出**可执行的优化清单**，由工程师据此改代码。

## 项目位置

`/root/workspace/quotasentry`。需要读的文件：

- 需求与设计规范：`docs/TEAM_CONTRACT.md` 的 §5（设计 token、页面结构、动效要求）
- 主题：`app/src/main/java/com/aicode/quotasentry/ui/theme/Theme.kt`
- 组件：`ui/components/Glass.kt`、`ui/components/Miuix.kt`、`ui/components/AppIcons.kt`
- 页面：`ui/screens/DashboardScreen.kt`、`SchedulesScreen.kt`、`SettingsScreen.kt`、`AccountSheet.kt`、`UiCommon.kt`、`UpdateDialog.kt`
- 根导航：`ui/QuotaSentryRoot.kt`
- 数据语义：`core/Models.kt`、`core/QuotaMath.kt`（额度来源 OFFICIAL/ESTIMATED/待刷新、5h 窗口、计划模式）

## 产品背景

「额度哨兵」：连接 Claude / ChatGPT / Gemini 账号，后台常驻，按计划（跟随 5 小时额度窗口 / 固定间隔 / 每天定时）
自动发一条很短的保活消息；首页看三家额度剩余、重置倒计时；支持 Chrome OAuth 授权、代理、自动更新。

用户对界面的明确要求（原话）：
1. **Liquid Glass（液体玻璃）设计**；
2. **MIUIX 风格**；
3. **滑动/切换必须丝滑**（逐帧跟手、弹簧释放，不要生硬的默认 fade/slide）；
4. 简单页面，别复杂；
5. 深色优先，同时要有浅色/跟随系统。

## 现有实现（供你判断，不要假设它已完美）

- 深色底 `#07090F`，`AuroraBackground` 用静态 Canvas 画 3–4 个柔光光斑，动画只改 `graphicsLayer`；
- `GlassCard` 半透明渐变填充 + 1dp 渐变描边 + 顶部高光 + 阴影，圆角 26–30dp，无 `Modifier.blur()`；
- MIUIX 风格组件：`MiuixSwitch`、`Slider`、`SegmentedControl`、`ListRow`、`SectionCard`、`TopBar`、`GlassSheet`；
- 三 Tab：首页 / 计划 / 设置，`HorizontalPager` + 弹簧指标 + 悬浮玻璃 Tab 栏 + stagger 入场；
- 首页：状态胶囊、概览卡、三家 Provider 额度卡（额度环、剩余条数、重置倒计时、来源徽标、立即续命/刷新、详情 Sheet、历史时间线）；
- 计划页：每家一张计划卡 + 开关 + 新增/编辑 Sheet；
- 设置页：账号连接（Chrome OAuth / Cookie）、额度与刷新、后台保活体检、网络代理、外观、维护与更新；
- 主题支持 `Settings.themeMode`（跟随/深/浅）、`Settings.accent`（5 种强调色）、`Settings.glassIntensity`（0.4–1.0）；
- 性能红线：玻璃层 ≤2，禁止全屏 blur，列表滚动不能逐帧重建模糊；低端机要能跑。

## 评审重点（请覆盖，但不要泛泛而谈）

1. **视觉层级**：首页信息是否过载？卡片/文字/数字的层级、留白、节奏是否像「高级玻璃」而不是「深色卡片堆」；
2. **玻璃质感**：现在的半透明+描边+光斑是否足够像 Liquid Glass？还缺什么（高光流动、边缘折射感、模糊层次、内外阴影、色彩渗透）？在不使用全屏 `blur()` 的前提下给具体 Compose 做法；
3. **MIUIX 味道**：圆角、开关、列表行、分段控件、按钮、弹层是否够「MIUIX/HyperOS」？指出具体尺寸/动效差异；
4. **动效**：Pager 转场、Tab blob、按压、卡片入场、Sheet 弹出是否够跟手/有物理感？给出具体 spring 参数与实现建议；
5. **中文排版**：字号阶梯、行高、字重、数字（额度百分比/倒计时）对齐与等宽处理；
6. **深浅色**：浅色主题目前只是 token 级支持，评估对比度/玻璃在浅色下的表现；
7. **信息设计**：OFFICIAL / ESTIMATED / 待刷新 三种额度来源、剩余条数 vs 百分比、倒计时语义是否一眼能懂；空状态/错误态/未连接态；
8. **可用性**：单手操作热区、Tab 栏遮挡、Sheet 键盘、长列表、触控目标 ≥48dp、无障碍对比度；
9. **性能**：哪些写法会在中低端机上掉帧（重组、绘制、阴影、渐变），给出替代方案。

## 输出要求

写到文件 `/root/workspace/quotasentry/docs/UI_REVIEW_OPUS.md`，结构：

1. **一句话总评** + 评分（视觉/交互/性能/一致性，各 1–10）；
2. **Top 8 必须改**：每条给「问题 → 具体改法 → 涉及文件/组件 → 预计工作量（S/M/L）」；
3. **分页面逐条评审**（首页/计划/设置/账号 Sheet/Tab 栏/弹窗），每条都落到具体代码或视觉参数；
4. **建议的新设计 token**（颜色、圆角、间距、字号、阴影、spring 参数），可直接替换现有值；
5. **不建议做的**（避免过度设计/性能陷阱）。

约束：不要建议引入 material3 / material-icons / 新依赖；不要建议全屏实时模糊；不要重写成 Web；方案必须能用现有 Compose foundation/ui/animation 实现。
另外请把你认为「最值得做的 3 个改动」写成可直接派给工程师的任务描述。

完成后用 send_message 给 lead 发一条摘要（不要贴整个文件）。

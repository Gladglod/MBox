# 首页设计评审：MBox（TVBoxOS 手机竖屏版）

对照 Apple Human Interface Guidelines。评审日期 2026-10-27。

---

## 范围与前提

- **平台 / 框架**：Android 原生（纯 XML + Java，无 Compose），竖屏，360dp 设计基准 + AutoSize。
- **Apple 规范的适用边界**：这是一个 Android-only 应用。按 HIG 自身的适用范围，它适用的是**基础层**（无障碍、色彩、字体、布局、文案）与**八条设计原则**；不适用的是 Apple 的**平台约定**（tab bar 的形态与位置、SF Symbols、Dynamic Type 的具体实现）。凡是"平台约定"类问题，下面会同时给出 Material 的对应要求。
- **评审材料**：`activity_home.xml`、`HomeActivity.java`、`HotRecommendFragment.java`、`item_grid.xml`、`colors.xml` / `values-night/colors.xml`、`dimens.xml`、`styles.xml`、`BaseActivity.java`、`GridFragment.java`。没有截图，因此尺寸取自布局常量（dp），对比度由十六进制值实算（WCAG 相对亮度公式），不是目测。
- **这一屏的唯一职责**：让人在 3 秒内挑到一部想看的片子。它的论点应该是"海报墙"。

---

## Summary

**评级：Critical issues。**

现在的首页是"四层横条 + 白底三列海报"，结构规整、token 体系干净，但没有一个能被记住的元素；唯一有个性的那抹霓虹青是从 TV 版继承来的，在白底上失效了（1.35:1）。三条 Critical 全部是基础层的：文字不响应系统字号、页签指示条几乎不可见、搜索入口对读屏软件不存在。

**固定 chrome 高度：52（顶栏）+ 1px + 44（页签）+ 56（底栏）+ 1px ≈ 152dp**，在 640dp 屏高上占 24%，海报墙被压成中段一条带子。

---

## Critical

### C1 所有文字写死在 dp，系统字体大小设置完全无效

**What**：`values/dimens.xml` 里 `ts_*` 全部是 dp（`ts_18`=14dp、`ts_14`=12dp、`ts_34`=20dp），并被直接用作 `android:textSize`。dp 不随 `fontScale` 缩放，所以用户在系统设置里把字号调到 130% 或 200%，首页每一个字都不变。全项目唯一用 sp 的地方是 `styles.xml:51` 的 `TextAppearance.App.Tab`（13sp）——于是只有页签文字会放大，而它所在的 44dp 页签条高度是写死的，会先被裁掉。

**Why**：`accessibility.md › Vision`："Support larger text sizes. Make sure people can adjust the size of their text or icons… Ideally, give people the option to enlarge text by at least 200 percent." `typography.md › Supporting Dynamic Type`："Make sure your app's layout adapts to all font sizes."

**Fix**：

1. `ts_*` 全部改为 sp（`<dimen name="ts_18">14sp</dimen>`）。
2. `activity_home.xml:112` 的 `layout_height="44dp"` 改为 `wrap_content` + `minHeight="44dp"`。
3. `item_grid.xml:126` 的 `tvName` 在放大后允许 2 行（`maxLines="2"`），`posterFrame` 高度改为按列宽等比。
4. 注意：AutoSize 只改写 density、不改 fontScale，改 sp 后两层缩放会叠加。务必在「360dp 基准 + fontScale=2.0」下过一遍首页与「我的」页。

### C2 页签指示条 #02F8E1 落在白底上 = 1.35:1

**What**：`activity_home.xml:114` `app:tabIndicatorColor="@color/color_02F8E1"`，2dp 高，落在 `@color/ui_surface` #FFFFFF 上，实算 **1.35:1**（落在 `ui_bg` #F3F4F7 上 1.23:1）。非文字控件需要 3:1。它还是"当前在第几页"的主要信号——未选中/选中的文字只是从 #99000000（5.74:1）变到 #17181D（16.1:1），同色相、亮度差很小。

这一行同时违反了项目自己在 `colors.xml:56-58` 写下的约定："页面/卡片/文字/分隔线一律引用这里的语义色，不要再写死 #0E0E12 这类字面量"。

**Why**：`accessibility.md › Vision`："To ensure all information in your app is legible, it's important that there's enough contrast between foreground text and icons and background colors." `color.md › System colors`："Avoid hard-coding system color values in your app."

**Fix**：

```xml
app:tabIndicatorColor="?attr/colorAccent"
```

并把日间 `ui_accent` 从 #00A396 改为 **#007A70**（白底 5.23:1、`ui_bg` 4.75:1）；夜间保持 #02F8E1（在 #0E0E12 上 14.2:1，很对）。这样指示条从 1.35:1 变成 5.23:1，且只改一个 token。

### C3 搜索按钮对读屏软件不存在

**What**：`activity_home.xml:59-68` 的 `ivSearch` 是纯 ImageView，`clickable="true"`，没有 `contentDescription`。全项目 11 处 `contentDescription` 全部是 `@null`（用于把装饰性图片标为无障碍忽略），没有一处是有意义的标签。TalkBack 读不出这个按钮，而它是进搜索的唯一入口。

**Why**：`icons.md › Best practices`："Provide alternative text labels for custom interface icons. Alternative text labels — or accessibility descriptions — aren't visible, but they let VoiceOver audibly describe what's onscreen."

**Fix**：

```xml
android:contentDescription="@string/action_search"
```

```xml
<string name="action_search">搜索影片</string>
```

文案用"动词 + 对象"，不要只写"搜索"——读屏时孤立的"搜索"不说明搜什么。底部四栏的容器也建议加 `android:labelFor`，避免读屏把图标和文字读成两个独立节点。

---

## Improvements

### H1 选中态文字 3.14:1（High）

**What**：`updateBottomNav()` 用 `ui_accent` #00A396 作选中色，落在白色底栏上是 **3.14:1**，而标签是 12dp 小字，需要 4.5:1。图标勉强过 3:1。另外 `activity_home.xml:173` 的 `tvNavHome` 初始 `textColor` 写死 `@color/color_02F8E1`，虽然运行时会被 `updateBottomNav(0)` 覆盖，但这是绝对色的残留。

**Fix**：`ui_accent` 日间改 #007A70（5.23:1）；删除 XML 里写死的 `#02F8E1`，改用 `@color/ui_accent`，让首帧和运行时一致。

### H2 「直播」不是页签，而是一个跳转，且选中态是错的（High）

**What**：`HomeActivity.java:281-286`，`selectBottomTab(2)` 直接 `startActivity(LivePlayActivity)` 并在给 `currentBottomTab` 赋值**之前** `return`。后果有两个：直播这一项永远不会亮；从直播页返回首页后，上一个页签仍然高亮着，位置指示是错的。

**Why**：`tab-bars.md › Best practices`："Use a tab bar to support navigation, not to provide actions." 以及 "Make sure the tab bar is visible when people navigate to different sections of your app. If you hide the tab bar, people can forget which area of the app they're in."

**Fix**：二选一——把直播做成 `flFragment` 里的一页（和「分类」「我的」共用 `showSubFragment`），或者把它从底部导航里拿掉、移进「我的」。

### H3 搜索被塞进右上角一个 40dp 的无文字图标（High）

**What**：对一个"找片看"的 App，搜索是主动作之一，现在是一个 40×40dp 的图标——低于 44pt 的默认触控目标，也低于 Material 的 48dp。

**Why**：`searching.md › Best practices`："If search is important, give it a primary position in your app or view." `cross-platform.md`：移动端应保证 "44 pt targets (48 dp on Material)"。

**Fix**：把顶栏左侧改成搜索框（假输入框，点击进 `SearchActivity`），站点名降为搜索框上方的一行 12dp 小字；或至少在内容区顶部放一条 44dp 的搜索条。

### H4 首屏加载时内容区整片空白（High）

**What**：`initData()` 调 `showLoading()`，而 LoadSir 是注册在 `topLayout`（52dp 顶栏）上的。于是加载时：顶栏被一个 16dp 的转圈替掉，页签条和海报区一片纯白，加载完才一次性出现。切换数据源时同理。

**Why**：`loading.md › Best practices`："Show something as soon as possible. If you make people wait for loading to complete before displaying anything, they can interpret the lack of content as a problem with your app or game."

**Fix**：`setLoadSir(this.topLayout)` 改为挂在 `homeContent`；加载态用 12 个海报骨架——`posterFrame` 填 `ui_poster_placeholder`，标题行用 60% / 40% 宽的两条灰条，和真实网格同尺寸，避免内容出现时的跳动。

### H5 默认强制浅色，忽略系统外观（High）

**What**：`HawkConfig.DEFAULT_UI_MODE = UI_MODE_DAY`。系统开了深色模式，App 首次启动仍然是白底，用户必须自己找到「我的 → 外观主题 → 跟随系统」。

**Why**：`color.md › Best practices`："Make sure all your app's colors work well in light, dark, and increased contrast contexts." Apple 同时明确要求不要提供应用自己的外观开关；Material 的预期是默认跟随系统。

**Fix**：`DEFAULT_UI_MODE` 改为 `UI_MODE_SYSTEM`，把「日间 / 夜间」保留为高级选项。这套 `ui_*` token 已经两边都写好了，改一个常量即可。

### H6 顶栏最大的一行字是数据源名，且和海报标题同号（High）

**What**：`tvName` 显示的是 `ApiConfig` 的站点名（`HomeActivity.java:437`），而 `refreshTopInfoTextSize()` 又把它的字号压到 `ts_20` = **14dp**，和海报标题 `ts_18` = 14dp 完全一样。结果是：全屏最显眼的一行字，是一个用户很可能没主动选过的后端站点名，而"这一页是什么"没有标题。

**Why**：`layout.md › Visual hierarchy`："Order content by relative importance. People often start by viewing content in reading order — that is, from top to bottom." `typography.md › Conveying hierarchy`："Adjust font weight, size, and color as needed to emphasize important information and help people visualize hierarchy."

**Fix**：顶栏标题改成页面名（「热门推荐」/当前分类名），站点名降级为 12dp 次级文字或移入「我的」；标题用 20dp / 500 权重，与 14dp 的卡片标题拉开两级。

### M1 落地页海报间距 0dp，分类页是 8dp（Medium）

**What**：`HotRecommendFragment` 没有 `addItemDecoration`；`GridFragment.java:256` 有 `SpacesItemDecoration(8dp)`。同一个网格两种呼吸感，而且更差的那个是默认落地页。三列海报在 360dp 宽上每张约 113dp，横向零间隙，读起来是一整块而不是 12 个条目。

**Why**：`layout.md › Visual hierarchy`："Group related items to clearly express related information or functions. For example, you might use negative space, container shapes, or separator lines to show which elements are related and which are unrelated."

**Fix**：在 `HotRecommendFragment.init()` 里加上同一套 `SpacesItemDecoration`（列间 8dp、外边 4dp），两处共用一个类。

### M2 「换源」胶囊：1.21:1、行话、32dp（Medium）

**What**：`bg_chip` 的 `ui_chip_bg` #E7E9EF 落在白色顶栏上 = **1.21:1**，边界几乎不可见，读起来像一行普通文字而不是按钮；高度 32dp（低于 44/48）；文案"换源"是行业黑话，用户不知道换的是什么。

**Why**：`writing.md › Best practices`："Choose simple, plain language and write with accessibility and localization in mind, avoiding jargon."

**Fix**：改成 44dp 高的图标按钮或「数据源 ›」文字按钮，底色加深并给 1dp 描边（`@color/ui_divider` 提到 #1F000000），文案改为「数据源」。

### M3 海报角标白字 3.36:1（Medium）

**What**：`bg_chip_on_image` 是 45% 黑（#73000000）。压在一张白海报上合成 #8C8C8C，白色 12dp 文字 = **3.36:1**；压在 #E8E8E8 上是 4.00:1。都不到 4.5:1。年份、备注这类角标恰恰是用户扫读时最先看的。

**Fix**：遮罩加深到 70%（#B3000000 → 白字 8.45:1），或改成海报底部渐变遮罩 + 文字 1px 阴影。两者都只在图片上生效，不影响主题切换。

### M4 空状态 2.09:1 且没有下一步（Medium）

**What**：`tvHotEmpty` 用 `ui_text_tertiary`（#4D000000 on #F3F4F7 = **2.09:1**），文案"暂无推荐内容"是纯陈述，没有动作。

**Why**：`writing.md › Best practices`："Provide clear next steps on any blank screens. … An empty screen can be daunting if it isn't obvious what to do next, so guide people on actions they can take, and give them a button or link to do so if possible."

**Fix**：文字改用 `ui_text_secondary`（5.74:1）；文案改为「这个数据源没有推荐内容」+ 一个「换个数据源」按钮，直接调已有的 `showSiteSwitch()`。

### M5 图标两套语言（Medium）

**What**：底部导航是 Material 24dp 实心字形（`ic_nav_home` 等），顶栏搜索是 1024 viewport 的描边图标（`icon_search.xml`）。粗细、细节层级、风格都不一致。

**Why**：`icons.md › Best practices`："Maintain visual consistency across all interface icons in your app. … all interface icons in your app need to use a consistent size, level of detail, stroke thickness (or weight), and perspective."

**Fix**：搜索图标换成同一套 24dp 实心字形（Material 的 `search`），或者全套改描边。二选一，别混。

### M6 顶部页签与底部「分类」是同一个东西（Medium）

**What**：顶部页签是分类列表，底部「分类」打开的 `CategoryFragment` 拿的还是 `getVisibleSortData()` 同一份列表，点进去又跳回顶部页签。同一份内容两个入口，而且占用了一格宝贵的底栏位置——而「历史」「收藏」这两个高频入口反而不在底栏。

**Why**：`design-principles.md › Simplicity`："Include just what's necessary. A well-designed experience removes the unnecessary, with every element earning its place."

**Fix**：二选一。建议保留顶部页签（可横滑，符合手势习惯），把底部第二项换成「历史」或「收藏」。

### M7 每秒刷新的时钟（Medium）

**What**：`HomeActivity.java:108-117` 的 `mRunnable` 每 1000ms 重设 `tvDate`，内容是 `yyyy/MM/dd E HH:mm`。这是 TV 版的遗留——电视没有系统状态栏，所以自绘时钟；手机状态栏就有时间。它每秒触发一次 `requestLayout`，还占着顶栏 1/3 的高度。

**Why**：`design-principles.md › Simplicity`："Establish hierarchy." / `Delight`："Don't mistake delight for decoration."

**Fix**：删掉，或只保留日期、每天刷一次。

### L1 分隔线用 1px 而不是 1dp（Low）

`activity_home.xml:94`、`:138` 用 `1px`。在 3x 屏上是 0.33dp，比 Material 的 1dp divider 细一半，日间几乎看不见；而夜间 1px 的 12% 白反而更明显——同一条线在两种主题下权重不同。改用 `1dp` + `@color/ui_divider`。

### L2 两套圆角（Low）

胶囊 16dp（`bg_chip`）、海报 8dp（`bg_poster`）。16dp 落在 32dp 高的胶囊上等于全圆角，和海报不是一套语言。统一成 8dp / 12dp 两档。

### L3 颜色名字与值不符（Low）

`colors.xml:8` `color_FFB800` 的值是 #FF6600；`:35` `color_99FFFFFF` 是 #4D000000；`:20` `color_CCFFFFFF` 是 #BBFFFFFF。名字撒谎，下一次改色会踩坑。旧色值可以保留，但至少加一行注释说明它们已被 `ui_*` 取代。

---

## Craft notes

**有没有观点：现在没有。** 四层横条（52 + 44 + 56 + 两条分隔线 = 152dp，640dp 屏高的 24%）把画面切成四条，海报墙被压成中段的一条带子。这一屏本该是"一面墙"，现在更像"一个表格"。删掉一条横条（见 M6 / M7）比任何视觉修饰都更有效。

**唯一的个性是那抹青，而它在日间是失效的。** #02F8E1 在夜间是对的（#0E0E12 上 14.2:1，很有电视霓虹感）；在日间是 1.35:1 的指示条和 3.14:1 的标签。这不是"配色不好"，是同一支颜色被要求在两块完全不同的底上工作。修法不是换色，是给它两个经过测量的档位：日间 #007A70、夜间 #02F8E1。

**排版没有个性可言。** 全站一套无衬线、一个 14dp/12dp 的两级刻度，顶栏标题和卡片标题同号（H6），于是"层级"实际上只有颜色在承担。一个"找片"的界面本可以让海报本身承担层级——更大的图、更少的话——现在图被压小了，话却还在。


**值得留的手艺**：`applyNavState()` 里那句"打断上一次未完成的过渡"（`HomeActivity.java:360-370`）——180ms、DecelerateInterpolator、从当前呈现值重定向。这是真做对了，而且是有意为之的注释。这种标准应该推广到页签指示条和海报加载上，而不是孤例。

**拿走一件**：顶栏那个每秒跳的日期（M7）。拿走它，顶栏就从"电视信息条"变回"页面标题栏"，H6 的标题层级问题也顺势解决了一半。

---

## What works

- **语义色 token 体系是真做了**：`colors.xml:54-97` 一整套 `ui_bg / ui_surface / ui_text_primary / secondary / divider / accent`，`values-night` 只覆盖这一层，播放器的深色浮层不受影响。这比大多数同类 App 干净，也让上面几条修复都变成"改一个 token"而不是"改一百个布局"。
- **触控目标整体够大**：底部四栏各 90dp 宽 × 56dp 高，远高于 44pt。
- **加载有节制**：首批 12 条、滑到底补 12 条，不是一次性把几十张海报丢给 Glide。
- **"重复点击 = 回到顶部"**：再点当前页签、已在首页再点「首页」都能回顶（`HomeActivity.java:187-190`、`:287-296`）。这个约定比很多成熟 App 都完整。
- **把信息架构的控制权交给用户**：长按拖动改首页标签顺序（`HomeTabSettingActivity`），符合 `design-principles.md › Agency`："Let them move through your interface and access features without being locked into specific flows."

---

## Platform notes

- 这是 Android 应用，Apple 的 tab bar / SF Symbols / Dynamic Type 这些**平台约定**不构成义务。但上面三条 Critical 全部落在**基础层**（字号可缩放、对比度、无障碍名称），跨平台都成立；Material 的要求只会更严（48dp 触控、4.5:1、TalkBack 标签）。
- 若以后要跨到 iOS：底部四栏 + 顶部可横滑页签的组合在 iOS 上会显得挤（iOS 的 tab bar 只有图标 + 单词，通常 ≤5 项）。届时应把分类页签降级为首页内的 segmented control，而不是和 tab bar 并列成两层导航。
- 修复顺序建议：**C1 → C2 / C3 → H4 / H5 / H6 → M1 / M2 / M3 → 其余**。前三条是"有人用不了"，后面是"用起来不顺"。

---

## 实施记录（2026-10-27）

按上表顺序落地，`./gradlew :app:assembleJava64Debug` 通过，已装到模拟器验证（见文末实测数据）。

| 条目            | 状态        | 落地方式                                                                                                                                                                                         |
| ------------- | --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1 字号不缩放      | 已修        | `dimens.xml` 的 `ts_*` 全部 dp→sp；顶栏、页签条改 `minHeight + wrap_content`；`item_grid.tvName` 允许 2 行                                                                                                  |
| C2 指示条 1.35:1 | 已修        | `tabIndicatorColor="?attr/colorAccent"` + `tabIndicatorFullWidth="false"`；日间 `ui_accent` #00A396→**#007A70**（5.23:1），`ui_accent_soft` 同步                                                     |
| C3 无障碍标签      | 已修        | `searchBar` / 四个导航项加 `contentDescription`，装饰图标 `importantForAccessibility="no"`，标签进 `strings.xml`                                                                                            |
| H1 选中态 3.14:1 | 已修        | 随 C2 的 token 一起解决（3.14→5.23）；删掉 `tvNavHome` 写死的 #02F8E1                                                                                                                                      |
| H2 直播不是页签     | **未做**    | 涉及导航结构取舍，见下方「待决策」                                                                                                                                                                            |
| H3 搜索位置       | 已修        | 顶栏左侧改成假搜索框（40dp 高、整块可点），搜索从"右上角图标"升为主动作                                                                                                                                                      |
| H4 加载区全空      | 已修        | LoadSir 从 52dp `topLayout` 移到 `homeContent`；新增 `HomeSkeletonCallback` + `loadsir_home_skeleton`（页签条 + 3×3 海报骨架，与真实网格同尺寸）；`BaseActivity` 增加 `showLoading(Class)` / `isShowingCallback(Class)` |
| H5 默认浅色       | 已修        | `HawkConfig.DEFAULT_UI_MODE` → `UI_MODE_SYSTEM`                                                                                                                                              |
| H6 标题是站点名     | 已修（改法有调整） | 见下方说明                                                                                                                                                                                        |
| M1 海报 0 间距    | 已修        | 抽出 `ui/widget/GridSpacingItemDecoration`，热门推荐与分类页共用 8dp                                                                                                                                      |
| M2 换源胶囊       | 已修        | 文案→站点名、高度→40dp、`bg_chip` 加 1dp `ui_divider` 描边、`maxWidth 120dp`                                                                                                                              |
| M3 角标 3.36:1  | 已修        | `bg_chip_on_image` 遮罩 45%→**70%**（白字 8.45:1）                                                                                                                                                 |
| M4 空状态 2.09:1 | 已修        | 文案改「这个数据源没有推荐内容」+「换个数据源」按钮（`onHomeEmptyAction()`：可切站点 >1 弹列表，否则进数据源地址页），文字改 `ui_text_secondary`                                                                                              |
| M5 图标两套       | 已修        | 新增 24dp 实心 `ic_search`，旧的 1024 viewport 描边 `icon_search` 已无引用                                                                                                                                |
| M6 分类重复       | **未做**    | 见下方「待决策」                                                                                                                                                                                     |
| M7 每秒时钟       | 已修        | 删掉 `mRunnable` 与 `tvDate`；切源反馈改为胶囊呼吸（`startSourceChipBlink()`）                                                                                                                               |
| L1 1px 分隔线    | 已修        | 两条分隔线改 1dp                                                                                                                                                                                   |
| L2 圆角两套       | 未做        | 全局 drawable，改动面大收益小，留待统一时一起处理                                                                                                                                                                |
| L3 颜色名说谎      | 已修（注释）    | `colors.xml` 顶部加说明，指向 `ui_*` 语义色                                                                                                                                                             |

### H6 的改法与评审建议不同

评审建议"顶栏标题改成页面名"。实施时发现：页签条就紧贴在顶栏下方、且已经在高亮当前页，  
再加一行同名标题就是同一个信息说两遍。所以实际改成**顶栏不要标题**——  
页签条自己承担"我在哪一页"，顶栏只留搜索与数据源；站点名从"标题"降级进右侧胶囊（12sp→13sp 的胶囊文字）。

### 待决策

- **H2 / M6**：底部「直播」是跳转而非页签、「分类」与顶部页签重复，这两条都属于信息架构取舍，  
  会改变用户已有的操作路径，没有单方面改。两个选项：①直播做成 `flFragment` 里的一页、②底部第二项换成「历史」或「收藏」。
- **L2**：`bg_chip` 的 16dp 圆角 vs 海报 8dp，属于全局视觉语言统一，建议单独一轮处理。

### 模拟器实测（540×960 / density 1.5 / 360dp 基准）

用 `uiautomator dump` 取到的真实布局尺寸：

| 元素           | 实测                 | 换算       | 结论                    |
| ------------ | ------------------ | -------- | --------------------- |
| `topLayout`  | 540×78             | 52dp     | 顶栏高度不变                |
| `searchBar`  | 318×60，desc=「搜索影片」 | 212×40dp | 40dp 触控高度，读屏有标签       |
| `tvMenu`     | 180×60，text=站点名    | 120×40dp | 站名进胶囊，命中 maxWidth     |
| `mTabLayout` | 540×72             | 48dp     | 由 44dp 撑到 48dp，字号可再放大 |
| 海报           | 146×243            | 97×162dp | 三列                    |
| 相邻海报间距       | 197−173 = 24       | 16dp     | 8dp+8dp 间距生效          |
| `bottomNav`  | 540×84             | 56dp     | 不变                    |
| `navHome`    | desc=「首页」          | —        | 读屏可读                  |

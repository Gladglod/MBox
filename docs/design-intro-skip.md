# 片头片尾自动跳过 · 实现说明

> 用户使用方法见根目录 `README.md` 的「片头片尾自动跳过」一节，本文记录实现细节与代码位置。

设置入口：「我的 → 播放设定 → 片头片尾 → 片头片尾设定」（`MineSettingActivity.SECTION_INTRO` 独立分组）。

## 模块组成

- **`api/IntroDbApi`**：片头片尾数据库远程接口（来源管理 + 查询 + IMDb 片名解析）。
- **`util/IntroSkipHelper`**：换集查询、进度轮询与自动 seek。
- **`util/GlassActionTip`**：片头跳过后的玻璃风格轻提示（含「撤销」）。

## 数据来源（`IntroDbApi.DbSource`）

数据库来源是一个**可管理的列表**：列表顺序（= 添加顺序）即**查询顺序**；来源可**同时启用多个**
（行内 switch 决定是否参与查询，不再单选 active），也可添加自定义接口。

**查询采用多来源顺序兜底**（`IntroDbApi.querySegments` → `queryNext`）：
按启用来源的添加顺序逐个尝试，某个来源失败或查不到数据就换下一个，全部落空才放弃；
一个来源都没启用时，回落到两个**公共服务**的默认地址。

**公共服务**：TheIntroDB / IntroDB 两个内置接口的默认配置（`ID_PUBLIC_THEINTRODB` / `ID_PUBLIC_INTRODB`），
可从列表删除，之后在「公共服务」分组重新添加（确认弹窗，加入查询队列末位，默认启用）。

查询格式分两种：

- **TheIntroDB v4**：`GET {url}/media?imdb_id=tt…&season=&episode=`
  —— 返回 `intro / recap / credits / preview` 四类区间数组，`start_ms` / `end_ms` 可为 null
  （片头从 0 开始 / 片尾直到片尾结束），`404 + code: no_submissions` 表示该集还没有人提交过；
- **IntroDB**：`GET {url}/segments?imdb_id=tt…`（电影 `is_movie=true`）
  —— 返回单个对象 `intro / outro`，缺失为 null；`no_intro` / `no_outro` 表示社区确认没有该区间。

两个库都只能按 IMDb tt 号查询，不支持按片名搜索，所以额外用
**IMDb 官方建议接口**（`v2.sg.media-imdb.com`，公开、免密钥）做「片名 → tt 号」解析。

## 跳过逻辑（`IntroSkipHelper`）

- **换集时** `onEpisodeStart(...)` 传入片名 / 年份 / 季集，先查本地缓存
  （键「片名|年份|季|集」，Hawk 存储，TTL 7 天，`HawkConfig.INTRO_SKIP_CACHE_TTL`）；
  没有缓存才联网查询。查询带序号，换集后旧请求的结果直接丢弃。
- **片头**：位置落在片头区间内（还没到片头结束点）时 seek 到片头结束点，每集只触发一次；
  跳过后弹轻提示，可**撤销**（seek 回 `fromPos`）。
- **片尾**：进入片尾后按设定的延迟秒数触发——片尾有已知结束点就 seek 过去，
  否则回调 `Listener.onSkipCreditsToEnd()`，由播放页走「下一集」
  （与手动设定 et 的逻辑一致）。
- **与手动设定共存**：解析到片头 / 片尾节点后通过 `ConfigHook.applyIntroOutro(...)` 写进
  播放器自身的片头片尾配置（`mVodPlayerCfg` 的 `st` 片头秒数 / `et` 片尾倒数秒数）——
  抽屉里的「片头 / 片尾」按钮随即显示这两个节点，用户可在此基础上手动微调；
  手动设过本集 et（`manualCredits`）时片尾交给 `VodController` 原有 st/et 机制，避免双重触发。
- **外部播放器**（`pl != 0`）不支持自动跳过，换集时直接跳过查询。
- 进度轮询间隔 500ms；进度未就绪（duration<=0）时最多等待 60 个 tick，防止空转。

## 设置项（`HawkConfig`）

| 键 | 说明 |
| --- | --- |
| `INTRO_SKIP_OPEN` | 自动跳过片头开关（默认开） |
| `INTRO_SKIP_CREDITS` | 自动跳过片尾开关（默认关） |
| `INTRO_SKIP_CREDITS_DELAY` | 进入片尾多少秒后触发（0~60，默认 3 秒） |
| `INTRO_DB_SOURCES` | 数据库来源列表（json 数组：`{id,name,url,format,builtIn,enabled}`；旧数据无 `enabled` 时公共服务默认启用、其余默认停用） |
| `INTRO_DB_ACTIVE` | 已废弃（单选时代遗留，不再读取） |
| `INTRO_DB_SOURCE` / `INTRO_DB_CUSTOM_URL` | 旧版单选配置，仅用于向来源列表迁移，不再读取 |

## 其他

- `IntroSkipHelper.enabled()` 只看两个跳过开关——来源一个都没启用时，查询会回落到公共服务默认配置，模块本身不用提前放弃。

## 自动画中画（`PipHelper`）

「播放设定 → 后台播放 → 自动画中画」（`HawkConfig.PIP_AUTO_ENTER`，默认关）：

- **Android 12+ 原生路径**：`PictureInPictureParams.setAutoEnterEnabled(true)`，
  播放中滑回桌面由系统自动进 PiP，无需 App 手动触发；
- **Android 8~11**：`DetailActivity.onUserLeaveHint()` →
  `PlayFragment.autoEnterOnUserLeave()` 按 `PIP_MODE` 分发——系统 PiP 模式手动
  `enter(...)`，自定义小窗模式走 `FloatPlayWindow`；
- **仅播放中生效**：`PipHelper.syncAutoEnterParams(activity, ratio, playing)` 随播放状态同步参数，
  暂停 / 出错时关掉 autoEnter，避免把冻结的暂停画面顶进小窗；仅在全屏播放且未处于任何小窗时调用；
- 与 `PIP_MODE` 的关系：`PIP_MODE = OFF` 时整体关闭；`OVERLAY`（自定义小窗）不走系统 autoEnter 路径。

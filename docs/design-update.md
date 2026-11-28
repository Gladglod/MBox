# 版本更新（GitHub Releases）· 实现说明

> 用户使用方法见根目录 `README.md` 的「检查更新」一节，本文记录实现细节、代码位置与发版约定。

仓库地址硬编码在 `UpdateChecker.REPO`（`https://github.com/Gladglod/MBox`）。

## 检查逻辑

- **自动检查**：启动首页后延迟 3 秒静默请求一次 `/releases/latest`，**24 小时内最多一次**
  （`HawkConfig.UPDATE_LAST_CHECK` 节流），只有真的发现新版才弹窗。
- **版本比较**：拿 release 的 `tag_name` 和本地 `versionName` 做**逐段数字比较**
  （`2.0.10 > 2.0.9`，`v2.0.1` 与 `2.0.1` 等价），相等或更旧都算「已是最新」。
  所以**发版时 `app/build.gradle` 的 `versionName` 必须和 tag 一致**，否则会误判。
- **忽略此版本**：把 tag 记进 `HawkConfig.UPDATE_IGNORE_TAG`，自动检查不再提示该版本，手动检查仍然会显示。

## 选包逻辑

按当前构建的 `BuildConfig.FLAVOR` 精确匹配 `MBox_release-<flavor>.apk`
（java / java32 / java64 / python…），匹配不到再按设备 ABI 退一步，最后退到任意 `.apk`；
都没有就提示「该版本没有提供与当前设备匹配的安装包」，主按钮变成「浏览器打开」。

## 下载与安装

更新弹窗把「怎么更新」交给用户选，初始状态并排两个入口：

- **浏览器下载**：打开 Releases 页面，由用户自行挑包下载，适合想自己保存安装包的场景；
- **软件内下载**：就地下载到 `getExternalCacheDir()/update/`（已完整下载过的包直接复用，
  不重复消耗流量），弹窗原地切换到进度态（百分比 + 已下载/总大小 + 取消下载），
  下载完自动拉起系统安装器，走 `FileProvider`（`${applicationId}.fileprovider`）。

下载中点「取消下载」会回到双入口状态；关闭弹窗同样会取消后台下载。
下载完成但被系统「安装未知应用」权限拦下时，弹窗保留「立即安装」按钮，授权返回后可重试。

## 失败兜底

GitHub API 连不上（国内网络常见）时，弹窗里的「浏览器下载」仍可直接跳 Releases 页面手动下载；
如果该版本没有与当前设备匹配的安装包，主按钮会变成「浏览器打开」。

## 发版约定（与代码里的选包逻辑对应）

| 构建产物 | Release 里的文件名 |
| --- | --- |
| `MBox_release-java.apk` | java 版（arm32 + arm64 合一） |
| `MBox_release-java32.apk` | java32 版 |
| `MBox_release-java64.apk` | java64 版 |

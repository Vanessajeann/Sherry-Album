# Sherry 相册管家 0.1.0-test3

本次版本根据用户堆栈修复阶段切换时进度值变为 NaN、导致 Compose 主线程退出的问题；进度条使用不可变快照，并补充真实界面回归。完整变更和复测范围见 `RELEASE-NOTES.md`。
应用内“导出诊断”可获取本应用错误记录和 Android 的历史退出原因；报告仅在用户选择导出时生成，不含图片内容，不自动发送。

基于 [SiRan-Dev/PhotoTimeFixer](https://github.com/SiRan-Dev/PhotoTimeFixer) 的 Android 二次开发版本。
原项目基线：`15948a12185e3ac8a27671a8c3512a094f734dd7`，GPL-3.0-or-later。

## 安装身份

- 应用名称：Sherry 相册管家
- applicationId / namespace：`io.github.vanessajeann.sherry.album`
- 与原版 `com.sirandev.phototimefixer` 使用不同的数据目录和安装身份，可以同时安装。
- minSdk 24；编译 / target SDK 36。回收站清理要求 Android 11 或更高版本。

## 新增照片去重

1. 完全重复：先按文件大小筛选候选，再完整读取并计算 SHA-256。
2. 包含相似照片：额外计算 EXIF 方向校正后的 pHash、dHash、颜色网格和画面方差。
3. 提供严格、均衡、宽松三档识别强度。相似组使用固定代表照片，避免相似关系沿连拍传递而合并大量不同照片。
4. 分组显示缩略图、分辨率、大小和目录，点击缩略图可进入系统相册查看原图。
5. 推荐保留优先依据分辨率、文件大小、拍摄日期，属于启发式排序，不代表一定保留了最佳照片。
6. 批量推荐只勾选完全重复组的副本；相似组需逐组确认，可点击“只保留这张”。任何一组至少保留一张。
7. 清理前核对媒体记录是否变化；完全重复组再次核对完整哈希，包括保留项。调用系统 `createTrashRequest`，每批最多 200 张，不直接永久删除文件。
8. 系统确认返回后，查询实际回收站状态，分别报告成功、取消和无法确认的结果。

## 限制

- 这是本应用实现的照片去重工作流，并非 Apple / 爱思的私有算法或无损元数据合并功能。不会把两张照片的 EXIF、收藏、相册归属、编辑记录自动合并。
- 相似识别可能漏报或误报，尤其是连拍、截图、曝光变化、裁剪和动态照片。相似组不会自动全选。
- GIF、WebP 的画面相似分析关闭，避免只比较首帧而误判动画；这些格式仍参与完全重复扫描。APNG 和运动照片的动态内容也不由图像指纹核对，需手动预览。
- HEIC / HEIF / AVIF 能否解码取决于系统支持；解码失败跳过相似分析，并保留完全重复检测能力。
- Android 14+ 若只授权部分照片，扫描范围仅限授权项。不会扫描云端尚未下载、私密空间或其他应用未公开的照片。
- 回收站保留期限、恢复入口、与云同步的行为由设备系统相册决定；移入回收站未必立即释放空间。
- 不需要 root。去重仅需照片权限。原项目时间修复仍需其原有的所有文件访问权限。
- 扫描可取消；旋转屏幕保留进度和分组。进程被系统杀死后需重新扫描。系统清理窗口被取消后不继续下一批。
- 所有分析在本机进行，Manifest 未申请网络权限。

## 构建

构建使用 JDK 21、Gradle 8.13、Android Gradle Plugin 8.13.0 和 Android SDK 36。
已根据实际 CI 下载结果调整上游不可用的 SDK 37 配置，并显式启用 Kotlin Android 插件和 AndroidX。

GitHub 仓库：[Vanessajeann/Sherry-Album](https://github.com/Vanessajeann/Sherry-Album)。内置 `.github/workflows/android.yml` 执行单元测试、lint 和 debug APK 构建，产物在 Actions 的 `SherryAlbum-0.1.0-test3` artifact 中。
`.github/workflows/release.yml` 仅在该工作流全部成功后发布测试版 APK；发布目标取产物中的 `BUILD-COMMIT.txt`，包含实际用于构建的源码提交。

本地已安装 Gradle 时：

```sh
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Wrapper JAR 已从固定上游提交取得，并校验 Git blob SHA。执行 `gradlew.bat testDebugUnitTest lintDebug assembleDebug`（Windows）或 `bash gradlew testDebugUnitTest lintDebug assembleDebug`（Linux/macOS）。若 JAR 遗失，可运行 `python bootstrap-wrapper.py` 恢复。

新版使用独立矢量图标，原作者捐赠入口保留为项目链接。

测试签名：首次 CI 在 `test-signing/` 生成并保存公开的 debug 测试密钥，后续构建复用，支持同一签名下覆盖更新。该密钥是公开的测试密钥，不能用于生产用途。正式签名可通过 `keystore/keystore.properties` 配置私有密钥；没有正式签名配置的 release 构建为未签名包。

## 验证状态

已恢复 GitHub 登录并接入云端构建。编译、JUnit 和 lint 的实际结果以仓库 Actions 为准；`SOURCE-VALIDATION.json` 仅记录最初的静态源码检查，不代表编译或真机测试。真机验证尚未完成。

核心算法回归测试覆盖：完全重复、哈希读取失败、颜色误判、低纹理画面、宽高比、压缩分辨率变体、相似链不传递、完全重复原子分组、选择保留保护，以及 BK 树与暴力搜索结果一致性。

后续需通过工作流构建，并在真机验证照片权限、部分授权、EXIF 旋转、HEIC 解码、系统回收站确认/取消、旋转屏幕与恢复流程。

原项目说明和许可证保留于 `README.md`、`LICENSE`、`THIRD_PARTY_NOTICES.md`。

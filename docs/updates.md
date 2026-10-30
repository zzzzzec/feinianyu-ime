# 更新与数据保留

## 使用

安装一次飞念语后，应用从 `zzzzzec/feinianyu-ime` 的最新正式 Release 读取 `update.json`。
后台通过 Android JobScheduler 每 12 小时检查，默认仅在非计费网络下载；Android 可能延后后台任务。
打开“应用更新”可立即检查。页面显示 Release 的中文 changelog、下载进度和“安装更新”。
下载完成后通知用户，安装仍由 Android 系统确认。未授予安装应用权限时，先跳转到系统授权页面。
不使用卸载重装，不清理应用数据。

## 校验

更新清单版本为 1，包含 applicationId 和按 ABI 划分的 APK 名称、versionCode、大小、SHA-256。
客户端只接受更高 versionCode、兼容 ABI、正确包名和与已安装应用完全相同的签名证书。
APK 流式下载到应用缓存，下载结束及安装前校验 SHA-256；版本、包名或签名不匹配时不安装。
只允许 HTTPS 的 GitHub 和 Release CDN 地址，不嵌入访问令牌。

## 版本与签名

- 固定应用包名：`dev.feinianyu.ime`。不要在版本间更换。
- 固定发行签名：`.private/personal-keyboard.p12` 及配套 signing.properties。必须一起私下备份。
- 默认 versionCode 为 `baseVersionCode × 10 + ABI 编号`，见 Versions.kt。每次发行增加 baseVersionCode。
- 版本展示名位于 app/build.gradle.kts。预发布测试可通过 personalVersionName/personalVersionCode 覆盖。
- 签名密钥、密码和个人数据不进入 Git 或 Release。

## 数据与配置

1. 覆盖安装保留应用目录中的剪贴板数据库、原生词库/学习历史、主题和设置。
2. 配置版本独立于应用版本，当前为 1。ConfigMigrations 在 AppPrefs 初始化前执行，先以 AtomicFile 保存带类型的配置快照，再提交迁移版本。
3. 新配置仅补缺失项，不覆盖用户已有值。重复启动不会重复迁移；备份失败时不开始修改。
4. 配置快照位于应用 noBackupFilesDir/config-backups；内部 restore API 可恢复配置，不触碰数据库或词库。
5. 剪贴板数据库当前为 Room schema 4，保留 1→2→3→4 的自动迁移和历史 schema。移除了降级时的破坏性重建。
6. 未来更改数据库结构须增加迁移并测试旧数据；不能用清库作为迁移失败回退。回滚应用使用更高 versionCode 的修复版本，避免强制数据库降级。
7. 模型和词表独立校验；缺失、加载失败或超时不阻止基础输入。

首次从上游或原实验包切换到飞念语属于不同包名，不会自动跨应用搬数据。之后飞念语各版本之间可覆盖升级。

## 发布

1. 完成模型、配置/数据库迁移和升级保留数据测试。
2. 增加版本号，编写 `docs/changelog/<版本>.md`，内容使用中文。
3. 提交并推送源码；使用固定签名构建各 ABI APK。
4. 发布脚本生成 update.json，并先上传为草稿 Release；确认附件齐全后发布。
5. Release 指向本次源码提交。手机会读到新版，显示中文更新内容并自动下载。

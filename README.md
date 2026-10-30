# 飞念语输入法 · Feinianyu IME

基于 Fcitx5 Android 的中文输入法，内置 Suzu-2.4 小模型，利用输入上文重排拼音候选。

[下载 APK](https://github.com/zzzzzec/feinianyu-ime/releases/latest) · [中文更新记录](docs/changelog/0.2.0.md) · [模型与字体设置](docs/suzu.md) · [更新机制](docs/updates.md)

## 当前功能

- 中文拼音、英文、数字、符号、剪贴板历史和文本编辑，保留 Fcitx5 原有输入能力。
- 内置约 44 MB 的 Suzu-2.4 Q8_0。模型在本机运行，只重排已有候选，不生成新候选。
- Gboard 风格浅色主题；键盘字体和候选字体可分别调整。
- 从本仓库 Release 自动检查和下载更新，更新页面显示中文 changelog；安装由 Android 系统确认。
- 固定包名 `dev.feinianyu.ime`，同一签名覆盖升级；配置按版本迁移，并在修改前保留快照。

个人训练数据采集、屏幕上下文、个性化训练仍属于后续计划，尚未实现。

## 安装与设置

手机通常使用 Release 中的 `arm64-v8a.apk`；`x86_64.apk` 供模拟器使用。
首次安装后按应用引导启用输入法，并在输入法列表中添加“拼音”。

“虚拟键盘”页面可调整：

- **Suzu 候选排序**：默认开启。
- **键盘字体大小**：12–32 dp，默认 20。
- **键盘候选字体大小**：12–32 sp，默认 18。

“应用更新”页面可查看中文更新说明、手动检查和安装已下载的版本。
自动下载默认使用 Wi-Fi，每 12 小时检查一次，实际后台执行时间由 Android 调度。
第一次应用内更新需要允许飞念语安装应用；之后仍需在系统安装界面确认。

这是独立应用包，与上游 Fcitx5 的数据目录分开。后续版本覆盖安装本应用会保留其数据；不要卸载后重装。

## 构建

工具链版本见 `toolchain-versions.json` 与上游 [构建说明](old.md)。

```powershell
git clone --recurse-submodules https://github.com/zzzzzec/feinianyu-ime.git
cd feinianyu-ime
./scripts/fetch-suzu.ps1
./gradlew.bat :app:assembleDebug -PbuildABI=arm64-v8a
```

首次个人发行前运行 `scripts/init-signing.ps1`，并私下备份 `.private/` 的签名文件。
发行签名不进入 Git；没有个人签名配置时，开发包使用 `.debug` 包名，避免与正式使用的数据目录混用。
模型固定版本下载并校验 SHA-256，不提交大权重到 Git；APK 内已包含模型，无需安装后下载。

## 来源与许可

组合发行遵守 GPL-3.0。原有文件保留其 SPDX 和版权声明；上游 LGPL-2.1 条款另保存在 `LICENSE-LGPL-2.1.txt`。
Suzu 权重为 CC BY-SA 4.0，llama.cpp 为 MIT，详见 [NOTICE.md](NOTICE.md)。

上游项目介绍保留在 [old.md](old.md)，早期产品计划保留在 [docs/personal-keyboard-plan.md](docs/personal-keyboard-plan.md)。

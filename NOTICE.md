# 来源与许可

飞念语输入法 / Feinianyu IME 是基于 Fcitx5 Android 的衍生项目。

- **Fcitx5 Android**：https://github.com/fcitx5-android/fcitx5-android 。保留原 Git 历史、版权声明及 SPDX。原项目许可证另存于 LICENSE-LGPL-2.1.txt。
- **Fcitx5、libime、fcitx5-chinese-addons 与其他子模块**：各自保留许可证和作者声明，见相应子模块。
- **Suzu-2.4 模型**：作者 IzumiThe3rd；https://huggingface.co/IzumiThe3rd/Suzu-2.4 。CC BY-SA 4.0，未修改的 Q8_0 权重。训练来源及致谢见 app/src/main/assets/suzu/NOTICE.md。
- **Suzu 推理层**：https://github.com/1zumiii/suzu-ime ，固定提交 a25db7850ed177bf05f5f79eee9a206e5586b955，仅复用 core/model；GPL-3.0。见 third_party/suzu/。
- **llama.cpp**：https://github.com/ggml-org/llama.cpp ，固定 b11632；MIT。

本项目增加了 Fcitx 候选排序适配、可调字号、浅色外观、配置迁移与应用更新。
组合 APK 按 GPL-3.0 分发；各组件及模型的独立许可证仍须遵守，不以本声明替代。
对应源码位于公开仓库 https://github.com/zzzzzec/feinianyu-ime ，Release 与其源码提交关联。
APK 内 assets/suzu/ 同时包含模型、推理组件的署名与许可文本。

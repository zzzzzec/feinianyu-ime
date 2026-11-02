# 语音输入组件与模型

- sherpa-onnx 2.0.1，Next-gen Kaldi / k2-fsa，Apache-2.0。
  https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1
  官方 static-link-onnxruntime AAR SHA-256：07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575。
- ONNX Runtime，Microsoft，MIT。包含在官方 sherpa-onnx Android 发行包中。
  https://github.com/microsoft/onnxruntime
- SenseVoice Small，Alibaba Group / FunAudioLLM，FunASR Model Open Source License Agreement v1.1。
  https://huggingface.co/FunAudioLLM/SenseVoiceSmall
  使用 csukuangfj 提供的未修改 int8 ONNX 转换文件。
  https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07
- Silero VAD，Silero Team，MIT。
  https://github.com/snakers4/silero-vad
  使用 csukuangfj/vad 的 silero_vad.onnx，固定提交 fba88cd2e921609e7675c3aaf51e0b9b295da4bc。

各许可全文见同目录 LICENSE-*.txt。识别和 VAD 权重由用户独立下载，不打进 APK。
固定下载地址、大小及 SHA-256 见 SpeechModelStore.kt 和 docs/speech-input.md。

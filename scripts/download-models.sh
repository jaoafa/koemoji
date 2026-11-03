#!/bin/sh
# Silero VAD と主な ASR モデルを ./data/models へ取得する (何度実行しても結果は同じ)。
set -e
B=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
mkdir -p data/models && cd data/models
[ -f silero_vad.onnx ] || curl -fsSL -o silero_vad.onnx $B/silero_vad.onnx
# 作業用ディレクトリへ展開して最後に名前を変えるので、ダウンロードが中断しても完成したモデルと誤認しない
fetch() {
  [ -d "$2" ] && return 0
  rm -rf "$2.part" && mkdir "$2.part"
  curl -fsSL "$B/$1.tar.bz2" | tar xj -C "$2.part"
  mv "$2.part/$1" "$2" && rmdir "$2.part"
}
fetch sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17 sensevoice
fetch sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17 sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17
fetch sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01 sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01
fetch sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
fetch sherpa-onnx-whisper-small sherpa-onnx-whisper-small

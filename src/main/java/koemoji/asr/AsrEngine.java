package koemoji.asr;

import koemoji.Config;

/** 差し替え可能な ASR バックエンド。サンプルは 16 kHz モノラルで、[-1, 1] の float。 */
public interface AsrEngine extends AutoCloseable {
  record Result(String text, double logProbabilitySum, int scoredTokenCount) {}

  record Capabilities(String name, boolean nativeStreaming, int maxInputSeconds, boolean punctuation) {}

  Capabilities capabilities();
  Result recognizePartial(float[] samples);
  Result recognizeFinal(float[] samples);
  @Override void close();

  static AsrEngine create(String name, Config c) {
    return new SherpaEngine(name, c);
  }
}

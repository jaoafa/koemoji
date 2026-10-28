package koemoji.asr;

import koemoji.Config;

import com.k2fsa.sherpa.onnx.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/**
 * Any offline sherpa-onnx model, selected by engine name. Offline models have no native streaming, so partials are
 * snapshot recognitions of the audio so far.
 */
public final class SherpaEngine implements AsrEngine {
  /** engine name -> model directory under MODELS_DIR */
  public static final Map<String, String> DIRS = Map.of(
      "sensevoice", "sensevoice",
      "reazon-ja-en", "sherpa-onnx-zipformer-ja-en-reazonspeech-2025-01-17",
      "reazon-ja", "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01",
      "qwen3-asr", "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
      "whisper-small", "sherpa-onnx-whisper-small",
      "whisper-turbo", "sherpa-onnx-whisper-turbo",
      "parakeet-ja", "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
      "dolphin-small", "sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02");

  private static final List<String> TRANSDUCER_ENGINES = List.of("reazon-ja", "reazon-ja-en");

  private final String name;
  private final OfflineRecognizer rec;

  /** 未対応のデコード方式を渡すと sherpa-onnx は例外ではなくプロセスごと終了するので、モデルを読む前にここで弾く。 */
  public static void requireDecoding(String engine, String method) {
    if (method.equals(Config.GREEDY_SEARCH)) return;
    if (!method.equals(Config.MODIFIED_BEAM_SEARCH)) throw new IllegalArgumentException("Unknown decoding method: " + method);
    if (!TRANSDUCER_ENGINES.contains(engine))
      throw new IllegalArgumentException("Decoding method " + method + " is not supported by engine " + engine
          + " (transducer engines only: " + TRANSDUCER_ENGINES + ")");
  }

  /** blank penalty が効くのは transducer 系だけで、他のエンジンでは黙って無視されるので、起動時に弾く。 */
  public static void requireBlankPenalty(String engine, float penalty) {
    if (penalty > 0 && !TRANSDUCER_ENGINES.contains(engine))
      throw new IllegalArgumentException("Blank penalty " + penalty + " is not supported by engine " + engine
          + " (transducer engines only: " + TRANSDUCER_ENGINES + ")");
  }

  public SherpaEngine(String name, Config c) {
    this.name = name;
    String sub = DIRS.get(name);
    if (sub == null) throw new IllegalArgumentException("Unknown engine: " + name + " (known: " + DIRS.keySet() + ")");
    requireDecoding(name, c.decodingMethodFor(name));
    requireBlankPenalty(name, c.blankPenaltyFor(name));
    Path d = c.modelsDir().resolve(sub);
    var mc = OfflineModelConfig.builder().setNumThreads(c.asrThreads()).setDebug(false).setProvider("cpu");
    switch (name) {
      case "sensevoice" -> mc.setSenseVoice(OfflineSenseVoiceModelConfig.builder()
              .setModel(d.resolve("model.int8.onnx").toString()).setLanguage(c.language())
              .setInverseTextNormalization(c.sensevoiceItn()).build())
          .setTokens(d.resolve("tokens.txt").toString());
      case "reazon-ja-en", "reazon-ja" -> mc.setTransducer(OfflineTransducerModelConfig.builder()
              .setEncoder(find(d, "encoder*.int8.onnx")).setDecoder(find(d, "decoder*.int8.onnx"))
              .setJoiner(find(d, "joiner*.int8.onnx")).build())
          .setTokens(d.resolve("tokens.txt").toString());
      case "qwen3-asr" -> mc.setQwen3Asr(OfflineQwen3AsrModelConfig.builder()
          .setConvFrontend(d.resolve("conv_frontend.onnx").toString())
          .setEncoder(d.resolve("encoder.int8.onnx").toString())
          .setDecoder(d.resolve("decoder.int8.onnx").toString())
          .setTokenizer(d.resolve("tokenizer").toString()).build());
      case "parakeet-ja" -> mc.setNemo(OfflineNemoEncDecCtcModelConfig.builder()
              .setModel(d.resolve("model.int8.onnx").toString()).build())
          .setTokens(d.resolve("tokens.txt").toString());
      case "dolphin-small" -> mc.setDolphin(OfflineDolphinModelConfig.builder()
              .setModel(d.resolve("model.int8.onnx").toString()).build())
          .setTokens(d.resolve("tokens.txt").toString());
      case "whisper-small", "whisper-turbo" -> mc.setWhisper(OfflineWhisperModelConfig.builder()
              .setEncoder(find(d, "*encoder*.int8.onnx")).setDecoder(find(d, "*decoder*.int8.onnx"))
              .setLanguage(c.language()).setTask("transcribe").build())
          .setTokens(find(d, "*tokens.txt"));
      default -> throw new IllegalStateException(name);
    }
    rec = new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(mc.build())
        .setDecodingMethod(c.decodingMethodFor(name)).setMaxActivePaths(c.maxActivePaths())
        .setBlankPenalty(c.blankPenaltyFor(name)).build());
  }

  private static String find(Path dir, String glob) {
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
      for (Path p : ds) return p.toString();
    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    throw new IllegalStateException("No " + glob + " in " + dir);
  }

  @Override public Capabilities capabilities() { return new Capabilities(name, false, 30, true); }
  @Override public Result recognizePartial(float[] s) { return run(s); }
  @Override public Result recognizeFinal(float[] s) { return run(s); }

  private Result run(float[] samples) {
    OfflineStream st = rec.createStream();
    try {
      st.acceptWaveform(samples, 16000);
      rec.decode(st);
      var result = rec.getResult(st);
      float[] logProbabilities = result.getYsLogProbs();
      if (logProbabilities.length != result.getTokens().length || logProbabilities.length == 0) {
        return new Result(result.getText().strip(), 0, 0);
      }
      double sum = 0;
      for (float logProbability : logProbabilities) {
        if (!Float.isFinite(logProbability)) return new Result(result.getText().strip(), 0, 0);
        sum += logProbability;
      }
      return new Result(result.getText().strip(), sum, logProbabilities.length);
    } finally { st.release(); }
  }

  @Override public void close() { rec.release(); }
}

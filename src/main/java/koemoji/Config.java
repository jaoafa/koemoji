package koemoji;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/** All tunables come from environment variables; the defaults live here. */
public record Config(
    String mode, String token, Path audioDir, Path queueDb,
    List<String> engines, Path modelsDir, Path vadModel, List<String> workerEngines, int asrThreads,
    int partialMs, int maxUtteranceMs, int audioTtlMin, int failedAudioTtlMin,
    float vadThreshold, int vadStartMs, int vadEndSilenceMs, int minUtteranceMs, int prerollMs,
    int retryMax, int retryBackoffMs, String language, int padMs, boolean includeBots, int healthPort,
    String messageFormat, String updateSecret,
    Map<String, Integer> padMsByEngine, List<String> normalizeEngines,
    float normalizeTargetDbfs, float normalizeMaxGainDb, float normalizePeak,
    String decodingMethod, Map<String, String> decodingMethodByEngine, int maxActivePaths,
    boolean sensevoiceItn, float normalizeSilenceDbfs, Map<String, Float> blankPenaltyByEngine) {

  public static final String DEFAULT_MESSAGE_FORMAT = "`{user}`: `{text}`";
  public static final String GREEDY_SEARCH = "greedy_search";
  public static final String MODIFIED_BEAM_SEARCH = "modified_beam_search";

  public static Config fromEnv() { return of(System::getenv); }

  public static Config of(Function<String, String> env) {
    BiFunction<String, String, String> s = (k, d) -> {
      String v = env.apply(k);
      return v == null || v.isBlank() ? d : v;
    };
    ToIntBiFunction<String, Integer> i = (k, d) -> {
      String v = s.apply(k, Integer.toString(d));
      try { return Integer.parseInt(v.trim()); }
      catch (NumberFormatException e) { throw new NumberFormatException(k + " must be an integer: " + v); }
    };
    return new Config(
        s.apply("MODE", "all"), env.apply("DISCORD_TOKEN"),
        Path.of(s.apply("AUDIO_DIR", "data/audio")), Path.of(s.apply("QUEUE_DB", "data/queue.db")),
        list(s.apply("ASR_ENGINES", "sensevoice")), Path.of(s.apply("MODELS_DIR", "data/models")),
        Path.of(s.apply("VAD_MODEL", "data/models/silero_vad.onnx")),
        workers(s.apply("WORKER_ENGINES", s.apply("ASR_ENGINES", "sensevoice"))), i.applyAsInt("ASR_THREADS", 4),
        i.applyAsInt("PARTIAL_INTERVAL_MS", 2000), i.applyAsInt("MAX_UTTERANCE_MS", 30_000),
        i.applyAsInt("AUDIO_TTL_MIN", 30), i.applyAsInt("FAILED_AUDIO_TTL_MIN", 1440),
        parseFloat("VAD_THRESHOLD", s.apply("VAD_THRESHOLD", "0.5")), i.applyAsInt("VAD_START_MS", 96),
        i.applyAsInt("VAD_END_SILENCE_MS", 1000), i.applyAsInt("MIN_UTTERANCE_MS", 300),
        i.applyAsInt("VAD_PREROLL_MS", 320), i.applyAsInt("RETRY_MAX", 5), i.applyAsInt("RETRY_BACKOFF_MS", 2000),
        s.apply("ASR_LANGUAGE", "ja"), i.applyAsInt("ASR_PAD_MS", 0),
        Boolean.parseBoolean(s.apply("INCLUDE_BOTS", "true")), i.applyAsInt("HEALTH_PORT", 8080),
        s.apply("MESSAGE_FORMAT", DEFAULT_MESSAGE_FORMAT), s.apply("UPDATE_CONTROL_SECRET", ""),
        padByEngine("ASR_PAD_MS_BY_ENGINE", s.apply("ASR_PAD_MS_BY_ENGINE", "")),
        names(s.apply("ASR_NORMALIZE_ENGINES", "")),
        parseFloat("ASR_NORMALIZE_TARGET_DBFS", s.apply("ASR_NORMALIZE_TARGET_DBFS", "-20")),
        parseFloat("ASR_NORMALIZE_MAX_GAIN_DB", s.apply("ASR_NORMALIZE_MAX_GAIN_DB", "20")),
        parseFloat("ASR_NORMALIZE_PEAK", s.apply("ASR_NORMALIZE_PEAK", "0.99")),
        decodingMethod("ASR_DECODING_METHOD", s.apply("ASR_DECODING_METHOD", GREEDY_SEARCH).trim()),
        byEngine("ASR_DECODING_METHOD_BY_ENGINE", s.apply("ASR_DECODING_METHOD_BY_ENGINE", ""), "method",
            (entry, method) -> decodingMethod("ASR_DECODING_METHOD_BY_ENGINE", method, entry)),
        maxActivePaths(i.applyAsInt("ASR_MAX_ACTIVE_PATHS", 4)),
        strictBoolean("ASR_SENSEVOICE_ITN", s.apply("ASR_SENSEVOICE_ITN", "true")),
        silenceDbfs(parseFloat("ASR_NORMALIZE_SILENCE_DBFS", s.apply("ASR_NORMALIZE_SILENCE_DBFS", "-60"))),
        blankPenaltyByEngine("ASR_BLANK_PENALTY_BY_ENGINE", s.apply("ASR_BLANK_PENALTY_BY_ENGINE", "")));
  }

  private static Map<String, Float> blankPenaltyByEngine(String key, String v) {
    return byEngine(key, v, "value", (entry, value) -> {
      float penalty;
      try { penalty = Float.parseFloat(value); }
      catch (NumberFormatException e) { throw new NumberFormatException(key + " value must be a number: " + entry); }
      if (!Float.isFinite(penalty) || penalty < 0) throw new IllegalArgumentException(key + " must be a finite number >= 0: " + entry);
      return penalty;
    });
  }

  /** replay は Main.validate を通らないので、新しい認識の設定の範囲は読み込み時に検証する。 */
  private static int maxActivePaths(int v) {
    if (v < 1) throw new IllegalArgumentException("ASR_MAX_ACTIVE_PATHS must be at least 1: " + v);
    return v;
  }

  private static float silenceDbfs(float v) {
    if (!Float.isFinite(v) || v > 0) throw new IllegalArgumentException("ASR_NORMALIZE_SILENCE_DBFS must be a finite number <= 0: " + v);
    return v;
  }

  private static String decodingMethod(String key, String method) { return decodingMethod(key, method, method); }

  private static String decodingMethod(String key, String method, String shown) {
    if (!method.equals(GREEDY_SEARCH) && !method.equals(MODIFIED_BEAM_SEARCH))
      throw new IllegalArgumentException(key + " must be " + GREEDY_SEARCH + " or " + MODIFIED_BEAM_SEARCH + ": " + shown);
    return method;
  }

  /** {@code Boolean.parseBoolean} は不正な値を黙って false にするので使わない。 */
  private static boolean strictBoolean(String key, String v) {
    if (v.trim().equalsIgnoreCase("true")) return true;
    if (v.trim().equalsIgnoreCase("false")) return false;
    throw new IllegalArgumentException(key + " must be true or false: " + v);
  }

  private static float parseFloat(String key, String v) {
    try { return Float.parseFloat(v.trim()); }
    catch (NumberFormatException e) { throw new NumberFormatException(key + " must be a number: " + v); }
  }

  public boolean capture() { return !mode.equals("worker"); }
  public boolean worker() { return !mode.equals("capture"); }

  public int padMsFor(String engine) { return padMsByEngine.getOrDefault(engine, padMs); }
  public boolean normalizes(String engine) { return normalizeEngines.contains(engine); }
  public String decodingMethodFor(String engine) { return decodingMethodByEngine.getOrDefault(engine, decodingMethod); }
  public float blankPenaltyFor(String engine) { return blankPenaltyByEngine.getOrDefault(engine, 0f); }

  private static List<String> names(String v) {
    return Arrays.stream(v.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
  }

  private static Map<String, Integer> padByEngine(String key, String v) {
    return byEngine(key, v, "ms", (entry, ms) -> {
      try { return Integer.parseInt(ms); }
      catch (NumberFormatException ex) { throw new NumberFormatException(key + " ms must be an integer: " + entry); }
    });
  }

  /** parse には、要素の全体(エラーメッセージ用)と、コロンより後ろの値を渡す。 */
  private static <T> Map<String, T> byEngine(String key, String v, String valueName, BiFunction<String, String, T> parse) {
    var out = new LinkedHashMap<String, T>();
    for (String e : names(v)) {
      int colon = e.indexOf(':');
      String name = colon < 0 ? "" : e.substring(0, colon).trim();
      if (name.isEmpty()) throw new IllegalArgumentException(key + " entries must be name:" + valueName + ": " + e);
      if (out.put(name, parse.apply(e, e.substring(colon + 1).trim())) != null)
        throw new IllegalArgumentException(key + " names an engine twice: " + name);
    }
    return Collections.unmodifiableMap(out);
  }

  private static List<String> list(String v) { return List.of(v.trim().split("\\s*,\\s*")); }

  /** "a:2,b" -> [a, a, b]: one entry per worker thread. */
  private static List<String> workers(String v) {
    var out = new ArrayList<String>();
    for (String e : list(v)) {
      String[] p = e.split(":");
      int count;
      try { count = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1; }
      catch (NumberFormatException ex) { throw new NumberFormatException("WORKER_ENGINES count must be an integer: " + e); }
      for (int n = count; n > 0; n--) out.add(p[0]);
    }
    return out;
  }
}

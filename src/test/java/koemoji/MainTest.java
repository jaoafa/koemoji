package koemoji;

import static org.junit.jupiter.api.Assertions.*;

import koemoji.asr.SherpaEngine;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainTest {
  private static Config config(Map<String, String> extra) throws Exception {
    var tmp = Files.createTempDirectory("cwmain");
    var env = new HashMap<>(extra);
    env.put("AUDIO_DIR", tmp.resolve("audio").toString());
    env.put("QUEUE_DB", tmp.resolve("q.db").toString());
    return Config.of(env::get);
  }

  @Test void onlyTheReplaySubcommandIsKnown() {
    assertNull(Main.subcommandError(new String[] {}));
    assertNull(Main.subcommandError(new String[] {"replay", "--engines", "sensevoice"}));
    assertEquals("Unknown subcommand: reply (known: replay)", Main.subcommandError(new String[] {"reply"}));
  }

  @Test void validateRejectsUnknownEnginesAndEnginesWithoutWorker() throws Exception {
    assertThrows(IllegalStateException.class, () -> Main.validate(config(java.util.Map.of("ASR_ENGINES", "nope"))));
    assertThrows(IllegalStateException.class, () -> Main.validate(config(java.util.Map.of(
        "ASR_ENGINES", "sensevoice,reazon-ja", "WORKER_ENGINES", "sensevoice"))));
    assertDoesNotThrow(() -> Main.validate(config(java.util.Map.of("ASR_ENGINES", "sensevoice,reazon-ja"))));
    assertDoesNotThrow(() -> Main.validate(config(java.util.Map.of(
        "MODE", "capture", "ASR_ENGINES", "sensevoice,reazon-ja", "WORKER_ENGINES", "sensevoice"))));
  }

  private static void assertRejected(Map<String, String> env, String message) {
    var e = assertThrows(IllegalStateException.class, () -> Main.validate(config(env)));
    assertEquals(message, e.getMessage());
  }

  @Test void validateRejectsAnUnknownEngineInTheDecodingMethodList() throws Exception {
    assertRejected(Map.of("ASR_DECODING_METHOD_BY_ENGINE", "nope:greedy_search"),
        "Unknown engine: nope in ASR_DECODING_METHOD_BY_ENGINE (known: " + SherpaEngine.DIRS.keySet() + ")");
  }

  @Test void validateRejectsADecodingMethodTheWorkersCannotRun() throws Exception {
    var unsupported = "Decoding method modified_beam_search is not supported by engine sensevoice (transducer engines only: [reazon-ja, reazon-ja-en])";
    assertRejected(Map.of("ASR_ENGINES", "sensevoice,reazon-ja", "ASR_DECODING_METHOD", "modified_beam_search"), unsupported);
    assertDoesNotThrow(() -> Main.validate(config(Map.of("ASR_ENGINES", "sensevoice,reazon-ja",
        "ASR_DECODING_METHOD_BY_ENGINE", "reazon-ja:modified_beam_search"))));
    assertRejected(Map.of("MODE", "worker", "ASR_ENGINES", "reazon-ja", "WORKER_ENGINES", "sensevoice",
        "ASR_DECODING_METHOD_BY_ENGINE", "sensevoice:modified_beam_search"), unsupported);
  }

  @Test void validateChecksTheDecodingMethodOnlyForEnginesThisProcessRuns() throws Exception {
    assertDoesNotThrow(() -> Main.validate(config(Map.of("MODE", "worker", "ASR_ENGINES", "sensevoice,reazon-ja",
        "WORKER_ENGINES", "reazon-ja", "ASR_DECODING_METHOD", "modified_beam_search"))));
    assertDoesNotThrow(() -> Main.validate(config(Map.of("MODE", "capture", "ASR_ENGINES", "sensevoice",
        "ASR_DECODING_METHOD", "modified_beam_search"))));
  }

  @Test void validateRejectsAnUnknownEngineInTheBlankPenaltyList() throws Exception {
    assertRejected(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", "nope:1.0"),
        "Unknown engine: nope in ASR_BLANK_PENALTY_BY_ENGINE (known: " + SherpaEngine.DIRS.keySet() + ")");
  }

  @Test void validateRejectsAnUnknownEngineInTheMinAvgLogProbList() throws Exception {
    assertRejected(Map.of("ASR_MIN_AVG_LOG_PROB_BY_ENGINE", "nope:-0.5"),
        "Unknown engine: nope in ASR_MIN_AVG_LOG_PROB_BY_ENGINE (known: " + SherpaEngine.DIRS.keySet() + ")");
  }

  @Test void validateRejectsABlankPenaltyTheWorkersCannotUse() throws Exception {
    var unsupported = "Blank penalty 1.0 is not supported by engine sensevoice (transducer engines only: [reazon-ja, reazon-ja-en])";
    assertRejected(Map.of("ASR_ENGINES", "sensevoice,reazon-ja", "ASR_BLANK_PENALTY_BY_ENGINE", "sensevoice:1.0"), unsupported);
    assertDoesNotThrow(() -> Main.validate(config(Map.of("ASR_ENGINES", "sensevoice,reazon-ja",
        "ASR_BLANK_PENALTY_BY_ENGINE", "reazon-ja:1.0,sensevoice:0"))));
  }

  @Test void validateChecksTheBlankPenaltyOnlyForEnginesThisProcessRuns() throws Exception {
    assertDoesNotThrow(() -> Main.validate(config(Map.of("MODE", "worker", "ASR_ENGINES", "sensevoice,reazon-ja",
        "WORKER_ENGINES", "reazon-ja", "ASR_BLANK_PENALTY_BY_ENGINE", "sensevoice:1.0"))));
    assertDoesNotThrow(() -> Main.validate(config(Map.of("MODE", "capture", "ASR_ENGINES", "sensevoice",
        "ASR_BLANK_PENALTY_BY_ENGINE", "sensevoice:1.0"))));
  }

  @Test void validateRejectsConfigsThatWouldDoNothing() throws Exception {
    assertThrows(IllegalStateException.class, () -> Main.validate(config(Map.of("ASR_ENGINES", ","))));
    assertThrows(IllegalStateException.class, () -> Main.validate(config(Map.of("MODE", "worker", "WORKER_ENGINES", "sensevoice:0"))));
    assertThrows(IllegalStateException.class, () -> Main.validate(config(Map.of("MAX_UTTERANCE_MS", "0"))));
    assertThrows(IllegalStateException.class, () -> Main.validate(config(Map.of("VAD_END_SILENCE_MS", "0"))));
  }

  private static void assertRejected(String key, String value, String named) throws Exception {
    var e = assertThrows(IllegalStateException.class, () -> Main.validate(config(Map.of(key, value))), key + "=" + value);
    assertTrue(e.getMessage().contains(named), e.getMessage());
  }

  @Test void validateRejectsUnknownEnginesInThePreprocessingSettings() throws Exception {
    assertRejected("ASR_PAD_MS_BY_ENGINE", "nope:100", "ASR_PAD_MS_BY_ENGINE");
    assertRejected("ASR_PAD_MS_BY_ENGINE", "nope:100", "nope");
    assertRejected("ASR_NORMALIZE_ENGINES", "nope", "ASR_NORMALIZE_ENGINES");
    assertRejected("ASR_NORMALIZE_ENGINES", "sensevoice,nope", "nope");
  }

  @Test void validateRejectsNegativePadding() throws Exception {
    assertRejected("ASR_PAD_MS", "-1", "ASR_PAD_MS");
    assertRejected("ASR_PAD_MS_BY_ENGINE", "sensevoice:-5", "ASR_PAD_MS_BY_ENGINE");
    assertRejected("ASR_PAD_MS_BY_ENGINE", "sensevoice:-5", "sensevoice:-5");
  }

  @Test void validateRejectsOutOfRangeNormalizationValues() throws Exception {
    assertRejected("ASR_NORMALIZE_MAX_GAIN_DB", "-1", "ASR_NORMALIZE_MAX_GAIN_DB");
    assertRejected("ASR_NORMALIZE_PEAK", "0", "ASR_NORMALIZE_PEAK");
    assertRejected("ASR_NORMALIZE_PEAK", "1.5", "ASR_NORMALIZE_PEAK");
    assertRejected("ASR_NORMALIZE_TARGET_DBFS", "3", "ASR_NORMALIZE_TARGET_DBFS");
  }

  @Test void validateRejectsNonFiniteNormalizationValues() throws Exception {
    assertRejected("ASR_NORMALIZE_TARGET_DBFS", "NaN", "ASR_NORMALIZE_TARGET_DBFS");
    assertRejected("ASR_NORMALIZE_MAX_GAIN_DB", "Infinity", "ASR_NORMALIZE_MAX_GAIN_DB");
    assertRejected("ASR_NORMALIZE_PEAK", "NaN", "ASR_NORMALIZE_PEAK");
  }

  @Test void validateAcceptsPreprocessingSettings() throws Exception {
    assertDoesNotThrow(() -> Main.validate(config(Map.of(
        "ASR_PAD_MS", "300", "ASR_PAD_MS_BY_ENGINE", "reazon-ja:600,sensevoice:0",
        "ASR_NORMALIZE_ENGINES", "reazon-ja", "ASR_NORMALIZE_TARGET_DBFS", "-23",
        "ASR_NORMALIZE_MAX_GAIN_DB", "0", "ASR_NORMALIZE_PEAK", "1"))));
    assertDoesNotThrow(() -> Main.validate(config(Map.of("ASR_PAD_MS_BY_ENGINE", "whisper-small:100"))));
  }

  @Test void validateRejectsUnknownMode() throws Exception {
    assertThrows(IllegalStateException.class, () -> Main.validate(config(Map.of("MODE", "workers"))));
    for (String m : new String[] {"all", "capture", "worker"}) assertDoesNotThrow(() -> Main.validate(config(Map.of("MODE", m))));
  }
}

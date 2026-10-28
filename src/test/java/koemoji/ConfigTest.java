package koemoji;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {
  private static Config of(Map<String, String> env) { return Config.of(env::get); }

  @Test void defaultsApplyWhenUnsetOrBlank() {
    var c = of(Map.of("ASR_THREADS", "  "));
    assertEquals(4, c.asrThreads());
    assertEquals(List.of("sensevoice"), c.engines());
    assertEquals("ja", c.language());
    assertTrue(c.includeBots());
    assertEquals(1000, c.vadEndSilenceMs());
    assertEquals(300, c.minUtteranceMs());
  }

  @Test void vadSilenceThresholdCanBeOverridden() {
    assertEquals(700, of(Map.of("VAD_END_SILENCE_MS", "700")).vadEndSilenceMs());
    assertEquals(450, of(Map.of("MIN_UTTERANCE_MS", "450")).minUtteranceMs());
  }

  @Test void workerEnginesDefaultToAsrEnginesAndExpandCounts() {
    assertEquals(List.of("a", "b"), of(Map.of("ASR_ENGINES", "a, b")).workerEngines());
    assertEquals(List.of("a", "a", "b"), of(Map.of("ASR_ENGINES", "a,b", "WORKER_ENGINES", "a:2,b")).workerEngines());
  }

  @Test void modeSelectsWhichSidesRun() {
    assertTrue(of(Map.of()).capture() && of(Map.of()).worker());
    assertTrue(of(Map.of("MODE", "capture")).capture() && !of(Map.of("MODE", "capture")).worker());
    assertTrue(!of(Map.of("MODE", "worker")).capture() && of(Map.of("MODE", "worker")).worker());
  }

  @Test void workerCountMustBeAnInteger() {
    var e = assertThrows(NumberFormatException.class, () -> of(Map.of("WORKER_ENGINES", "a:x")));
    assertTrue(e.getMessage().contains("WORKER_ENGINES"));
  }

  @Test void preprocessingDefaultsLeaveNormalizationOffAndPadEveryEngineAlike() {
    var c = of(Map.of());
    assertEquals(0, c.padMsFor("sensevoice"));
    assertEquals(0, c.padMsFor("reazon-ja"));
    assertFalse(c.normalizes("reazon-ja"));
    assertEquals(-20f, c.normalizeTargetDbfs());
    assertEquals(20f, c.normalizeMaxGainDb());
    assertEquals(0.99f, c.normalizePeak());
  }

  @Test void asrPadMsAloneAppliesToEveryEngine() {
    var c = of(Map.of("ASR_PAD_MS", "300"));
    assertEquals(300, c.padMsFor("sensevoice"));
    assertEquals(300, c.padMsFor("reazon-ja"));
  }

  @Test void padMsByEngineOverridesOnlyTheNamedEngines() {
    var c = of(Map.of("ASR_PAD_MS", "300", "ASR_PAD_MS_BY_ENGINE", "reazon-ja:600,sensevoice:0"));
    assertEquals(600, c.padMsFor("reazon-ja"));
    assertEquals(0, c.padMsFor("sensevoice"));
    assertEquals(300, c.padMsFor("whisper-small"));
  }

  @Test void padMsByEngineIgnoresWhitespaceAndEmptyElements() {
    var c = of(Map.of("ASR_PAD_MS_BY_ENGINE", " reazon-ja : 600 ,, sensevoice:0, "));
    assertEquals(600, c.padMsFor("reazon-ja"));
    assertEquals(0, c.padMsFor("sensevoice"));
    assertEquals(Map.of(), of(Map.of("ASR_PAD_MS_BY_ENGINE", "  ")).padMsByEngine());
  }

  @Test void padMsByEngineRejectsMalformedElementsNamingTheVariableAndValue() {
    var notAnInteger = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_PAD_MS_BY_ENGINE", "reazon-ja:long")));
    assertTrue(notAnInteger.getMessage().contains("ASR_PAD_MS_BY_ENGINE"));
    assertTrue(notAnInteger.getMessage().contains("reazon-ja:long"));
    var noColon = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_PAD_MS_BY_ENGINE", "reazon-ja")));
    assertTrue(noColon.getMessage().contains("ASR_PAD_MS_BY_ENGINE"));
    assertTrue(noColon.getMessage().contains("reazon-ja"));
    var noName = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_PAD_MS_BY_ENGINE", ":300")));
    assertTrue(noName.getMessage().contains("ASR_PAD_MS_BY_ENGINE"));
    assertTrue(noName.getMessage().contains(":300"));
    var duplicate = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_PAD_MS_BY_ENGINE", "reazon-ja:1,reazon-ja:2")));
    assertTrue(duplicate.getMessage().contains("ASR_PAD_MS_BY_ENGINE"));
    assertTrue(duplicate.getMessage().contains("reazon-ja"));
  }

  @Test void normalizeEnginesSelectsOnlyTheListedEngines() {
    var c = of(Map.of("ASR_NORMALIZE_ENGINES", " reazon-ja , "));
    assertTrue(c.normalizes("reazon-ja"));
    assertFalse(c.normalizes("sensevoice"));
    assertEquals(List.of(), of(Map.of("ASR_NORMALIZE_ENGINES", "  ")).normalizeEngines());
  }

  @Test void normalizationParametersCanBeOverridden() {
    var c = of(Map.of("ASR_NORMALIZE_TARGET_DBFS", "-23", "ASR_NORMALIZE_MAX_GAIN_DB", "12", "ASR_NORMALIZE_PEAK", "0.9"));
    assertEquals(-23f, c.normalizeTargetDbfs());
    assertEquals(12f, c.normalizeMaxGainDb());
    assertEquals(0.9f, c.normalizePeak());
    var e = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_NORMALIZE_PEAK", "loud")));
    assertTrue(e.getMessage().contains("ASR_NORMALIZE_PEAK"));
  }

  @Test void recognitionTunablesDefaultToTheBehaviorBeforeTheyWereConfigurable() {
    var c = of(Map.of());
    assertEquals("greedy_search", c.decodingMethod());
    assertEquals(Map.of(), c.decodingMethodByEngine());
    assertEquals("greedy_search", c.decodingMethodFor("sensevoice"));
    assertEquals("greedy_search", c.decodingMethodFor("reazon-ja"));
    assertEquals(4, c.maxActivePaths());
    assertTrue(c.sensevoiceItn());
    assertEquals(-60f, c.normalizeSilenceDbfs());
  }

  @Test void decodingMethodAppliesToEveryEngineUnlessAnEngineOverridesIt() {
    var all = of(Map.of("ASR_DECODING_METHOD", "modified_beam_search"));
    assertEquals("modified_beam_search", all.decodingMethodFor("sensevoice"));
    assertEquals("modified_beam_search", all.decodingMethodFor("reazon-ja"));
    var one = of(Map.of("ASR_DECODING_METHOD_BY_ENGINE", " reazon-ja:modified_beam_search , "));
    assertEquals("modified_beam_search", one.decodingMethodFor("reazon-ja"));
    assertEquals("greedy_search", one.decodingMethodFor("sensevoice"));
    var mixed = of(Map.of("ASR_DECODING_METHOD", "modified_beam_search", "ASR_DECODING_METHOD_BY_ENGINE", "sensevoice:greedy_search"));
    assertEquals("greedy_search", mixed.decodingMethodFor("sensevoice"));
    assertEquals("modified_beam_search", mixed.decodingMethodFor("reazon-ja"));
  }

  @Test void decodingMethodRejectsUnknownMethodsNamingTheVariableAndValue() {
    var global = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_DECODING_METHOD", "beam")));
    assertEquals("ASR_DECODING_METHOD must be greedy_search or modified_beam_search: beam", global.getMessage());
    var perEngine = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_DECODING_METHOD_BY_ENGINE", "reazon-ja:beam")));
    assertEquals("ASR_DECODING_METHOD_BY_ENGINE must be greedy_search or modified_beam_search: reazon-ja:beam", perEngine.getMessage());
    var upper = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_DECODING_METHOD", "GREEDY_SEARCH")));
    assertTrue(upper.getMessage().contains("GREEDY_SEARCH"));
  }

  @Test void decodingMethodByEngineRejectsMalformedElementsNamingTheVariableAndValue() {
    var noColon = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_DECODING_METHOD_BY_ENGINE", "reazon-ja")));
    assertEquals("ASR_DECODING_METHOD_BY_ENGINE entries must be name:method: reazon-ja", noColon.getMessage());
    var noName = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_DECODING_METHOD_BY_ENGINE", ":greedy_search")));
    assertEquals("ASR_DECODING_METHOD_BY_ENGINE entries must be name:method: :greedy_search", noName.getMessage());
    var duplicate = assertThrows(IllegalArgumentException.class,
        () -> of(Map.of("ASR_DECODING_METHOD_BY_ENGINE", "reazon-ja:greedy_search,reazon-ja:modified_beam_search")));
    assertEquals("ASR_DECODING_METHOD_BY_ENGINE names an engine twice: reazon-ja", duplicate.getMessage());
  }

  @Test void blankPenaltyDefaultsToZeroAndAppliesPerEngine() {
    assertEquals(0f, of(Map.of()).blankPenaltyFor("reazon-ja"));
    var c = of(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", " reazon-ja:1.0 , reazon-ja-en:0.6 , "));
    assertEquals(1.0f, c.blankPenaltyFor("reazon-ja"));
    assertEquals(0.6f, c.blankPenaltyFor("reazon-ja-en"));
    assertEquals(0f, c.blankPenaltyFor("sensevoice"));
  }

  @Test void blankPenaltyRejectsValuesOutsideItsRangeNamingTheEntry() {
    var notNumber = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", "reazon-ja:x")));
    assertEquals("ASR_BLANK_PENALTY_BY_ENGINE value must be a number: reazon-ja:x", notNumber.getMessage());
    for (String v : new String[] {"-0.1", "NaN", "Infinity"}) {
      var e = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", "reazon-ja:" + v)));
      assertEquals("ASR_BLANK_PENALTY_BY_ENGINE must be a finite number >= 0: reazon-ja:" + v, e.getMessage());
    }
    assertEquals(0f, of(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", "reazon-ja:0")).blankPenaltyFor("reazon-ja"));
  }

  @Test void blankPenaltyRejectsMalformedElementsNamingTheVariableAndValue() {
    var noColon = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", "reazon-ja")));
    assertEquals("ASR_BLANK_PENALTY_BY_ENGINE entries must be name:value: reazon-ja", noColon.getMessage());
    var noName = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", ":1.0")));
    assertEquals("ASR_BLANK_PENALTY_BY_ENGINE entries must be name:value: :1.0", noName.getMessage());
    var duplicate = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_BLANK_PENALTY_BY_ENGINE", "reazon-ja:1,reazon-ja:2")));
    assertEquals("ASR_BLANK_PENALTY_BY_ENGINE names an engine twice: reazon-ja", duplicate.getMessage());
  }

  @Test void discardThresholdsDefaultToPeakOnlyAndApplyPerEngine() {
    var defaults = of(Map.of());
    assertEquals(0.05f, defaults.minPeak());
    assertEquals(Float.NEGATIVE_INFINITY, defaults.minAvgLogProbFor("reazon-ja"));
    var c = of(Map.of("ASR_MIN_PEAK", "0", "ASR_MIN_AVG_LOG_PROB_BY_ENGINE", " reazon-ja:-0.5 , "));
    assertEquals(0f, c.minPeak());
    assertEquals(-0.5f, c.minAvgLogProbFor("reazon-ja"));
    assertEquals(Float.NEGATIVE_INFINITY, c.minAvgLogProbFor("sensevoice"));
    assertEquals(0f, of(Map.of("ASR_MIN_AVG_LOG_PROB_BY_ENGINE", "reazon-ja:0")).minAvgLogProbFor("reazon-ja"));
    assertEquals(1f, of(Map.of("ASR_MIN_PEAK", "1")).minPeak());
  }

  @Test void minPeakRejectsValuesOutsideItsRangeNamingTheValue() {
    var notNumber = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_MIN_PEAK", "x")));
    assertEquals("ASR_MIN_PEAK must be a number: x", notNumber.getMessage());
    for (String v : new String[] {"-0.1", "1.5", "NaN", "Infinity"}) {
      var e = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_MIN_PEAK", v)));
      assertEquals("ASR_MIN_PEAK must be a finite number in [0, 1]: " + Float.parseFloat(v), e.getMessage());
    }
  }

  @Test void minAvgLogProbRejectsValuesOutsideItsRangeNamingTheEntry() {
    var notNumber = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_MIN_AVG_LOG_PROB_BY_ENGINE", "reazon-ja:x")));
    assertEquals("ASR_MIN_AVG_LOG_PROB_BY_ENGINE value must be a number: reazon-ja:x", notNumber.getMessage());
    for (String v : new String[] {"0.1", "NaN", "Infinity", "-Infinity"}) {
      var e = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_MIN_AVG_LOG_PROB_BY_ENGINE", "reazon-ja:" + v)));
      assertEquals("ASR_MIN_AVG_LOG_PROB_BY_ENGINE must be a finite number <= 0: reazon-ja:" + v, e.getMessage());
    }
  }

  @Test void minAvgLogProbRejectsMalformedElementsNamingTheVariableAndValue() {
    var noColon = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_MIN_AVG_LOG_PROB_BY_ENGINE", "reazon-ja")));
    assertEquals("ASR_MIN_AVG_LOG_PROB_BY_ENGINE entries must be name:value: reazon-ja", noColon.getMessage());
    var noName = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_MIN_AVG_LOG_PROB_BY_ENGINE", ":-0.5")));
    assertEquals("ASR_MIN_AVG_LOG_PROB_BY_ENGINE entries must be name:value: :-0.5", noName.getMessage());
    var duplicate = assertThrows(IllegalArgumentException.class,
        () -> of(Map.of("ASR_MIN_AVG_LOG_PROB_BY_ENGINE", "reazon-ja:-1,reazon-ja:-2")));
    assertEquals("ASR_MIN_AVG_LOG_PROB_BY_ENGINE names an engine twice: reazon-ja", duplicate.getMessage());
  }

  @Test void recognitionTunablesCanBeOverridden() {
    var c = of(Map.of("ASR_MAX_ACTIVE_PATHS", "8", "ASR_SENSEVOICE_ITN", "FALSE", "ASR_NORMALIZE_SILENCE_DBFS", "-45.5"));
    assertEquals(8, c.maxActivePaths());
    assertFalse(c.sensevoiceItn());
    assertEquals(-45.5f, c.normalizeSilenceDbfs());
    assertTrue(of(Map.of("ASR_SENSEVOICE_ITN", "True")).sensevoiceItn());
  }

  @Test void recognitionTunablesOutsideTheirRangeAreRejected() {
    for (String v : new String[] {"0", "-1"}) {
      var e = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_MAX_ACTIVE_PATHS", v)));
      assertEquals("ASR_MAX_ACTIVE_PATHS must be at least 1: " + v, e.getMessage());
    }
    assertEquals(1, of(Map.of("ASR_MAX_ACTIVE_PATHS", "1")).maxActivePaths());
    assertEquals("ASR_NORMALIZE_SILENCE_DBFS must be a finite number <= 0: 1.0",
        assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_NORMALIZE_SILENCE_DBFS", "1"))).getMessage());
    for (String v : new String[] {"NaN", "Infinity", "-Infinity"})
      assertTrue(assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_NORMALIZE_SILENCE_DBFS", v))).getMessage()
          .startsWith("ASR_NORMALIZE_SILENCE_DBFS must be a finite number <= 0: "));
    assertEquals(0f, of(Map.of("ASR_NORMALIZE_SILENCE_DBFS", "0")).normalizeSilenceDbfs());
  }

  @Test void invalidRecognitionTunablesAreRejectedNamingTheVariableAndValue() {
    var paths = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_MAX_ACTIVE_PATHS", "many")));
    assertEquals("ASR_MAX_ACTIVE_PATHS must be an integer: many", paths.getMessage());
    var itn = assertThrows(IllegalArgumentException.class, () -> of(Map.of("ASR_SENSEVOICE_ITN", "maybe")));
    assertEquals("ASR_SENSEVOICE_ITN must be true or false: maybe", itn.getMessage());
    var silence = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_NORMALIZE_SILENCE_DBFS", "abc")));
    assertEquals("ASR_NORMALIZE_SILENCE_DBFS must be a number: abc", silence.getMessage());
  }

  @Test void invalidNumbersAreRejected() {
    var e = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_THREADS", "many")));
    assertTrue(e.getMessage().contains("ASR_THREADS"));
    assertTrue(assertThrows(NumberFormatException.class, () -> of(Map.of("VAD_THRESHOLD", "high"))).getMessage().contains("VAD_THRESHOLD"));
  }
}

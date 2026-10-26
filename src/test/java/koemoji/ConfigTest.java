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

  @Test void invalidNumbersAreRejected() {
    var e = assertThrows(NumberFormatException.class, () -> of(Map.of("ASR_THREADS", "many")));
    assertTrue(e.getMessage().contains("ASR_THREADS"));
    assertTrue(assertThrows(NumberFormatException.class, () -> of(Map.of("VAD_THRESHOLD", "high"))).getMessage().contains("VAD_THRESHOLD"));
  }
}

package koemoji.asr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SherpaEngineDecodingTest {
  private static final List<String> TRANSDUCERS = List.of("reazon-ja", "reazon-ja-en");
  private static final List<String> OTHERS = List.of("sensevoice", "parakeet-ja", "whisper-small", "whisper-turbo", "qwen3-asr", "dolphin-small");

  @Test void greedySearchIsAcceptedByEveryEngine() {
    for (String engine : SherpaEngine.DIRS.keySet()) assertDoesNotThrow(() -> SherpaEngine.requireDecoding(engine, "greedy_search"));
  }

  @Test void modifiedBeamSearchIsAcceptedOnlyByTransducerEngines() {
    for (String engine : TRANSDUCERS) assertDoesNotThrow(() -> SherpaEngine.requireDecoding(engine, "modified_beam_search"));
    for (String engine : OTHERS) {
      var e = assertThrows(IllegalArgumentException.class, () -> SherpaEngine.requireDecoding(engine, "modified_beam_search"));
      assertEquals("Decoding method modified_beam_search is not supported by engine " + engine
          + " (transducer engines only: [reazon-ja, reazon-ja-en])", e.getMessage());
    }
  }

  @Test void unknownMethodsAreRejectedForEveryEngine() {
    for (String engine : List.of("reazon-ja", "sensevoice")) {
      var e = assertThrows(IllegalArgumentException.class, () -> SherpaEngine.requireDecoding(engine, "beam"));
      assertEquals("Unknown decoding method: beam", e.getMessage());
    }
  }

  @Test void blankPenaltyOfZeroIsAcceptedByEveryEngine() {
    for (String engine : SherpaEngine.DIRS.keySet()) assertDoesNotThrow(() -> SherpaEngine.requireBlankPenalty(engine, 0f));
  }

  @Test void aPositiveBlankPenaltyIsAcceptedOnlyByTransducerEngines() {
    for (String engine : TRANSDUCERS) assertDoesNotThrow(() -> SherpaEngine.requireBlankPenalty(engine, 1.0f));
    for (String engine : OTHERS) {
      var e = assertThrows(IllegalArgumentException.class, () -> SherpaEngine.requireBlankPenalty(engine, 0.5f));
      assertEquals("Blank penalty 0.5 is not supported by engine " + engine
          + " (transducer engines only: [reazon-ja, reazon-ja-en])", e.getMessage());
    }
  }

  @Test void everyEngineIsClassifiedAsTransducerOrNot() {
    var all = new java.util.TreeSet<>(SherpaEngine.DIRS.keySet());
    var classified = new java.util.TreeSet<String>();
    classified.addAll(TRANSDUCERS);
    classified.addAll(OTHERS);
    assertEquals(all, classified);
  }
}

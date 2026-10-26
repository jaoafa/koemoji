package koemoji.asr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Map;
import koemoji.Config;
import org.junit.jupiter.api.Test;

class WorkerTest {
  private static final float TOLERANCE = 1e-6f;

  private static Config of(Map<String, String> env) { return Config.of(env::get); }

  private static float[] filled(float v, int n) {
    float[] x = new float[n];
    Arrays.fill(x, v);
    return x;
  }

  private static void assertAllEqual(float expected, float[] actual) {
    for (int i = 0; i < actual.length; i++) assertEquals(expected, actual[i], TOLERANCE, "sample " + i);
  }

  @Test void padAddsSilenceToBothEndsAtSixteenSamplesPerMs() {
    float[] out = Worker.pad(new float[] {1f, 2f}, 2);
    assertEquals(2 + 2 * 32, out.length);
    assertEquals(1f, out[32]);
    assertEquals(2f, out[33]);
    assertEquals(0f, out[0]);
    assertEquals(0f, out[out.length - 1]);
  }

  @Test void padOfZeroOrLessLeavesTheAudioUntouched() {
    float[] x = {1f};
    assertSame(x, Worker.pad(x, 0));
    assertSame(x, Worker.pad(x, -5));
  }

  @Test void normalizeAttenuatesLoudAudioToTheTargetRms() {
    assertAllEqual(0.1f, Worker.normalize(filled(0.5f, 100), -20f, 20f, 0.99f));
  }

  @Test void normalizeAmplifiesQuietAudioToTheTargetRmsWhenTheGainCapAllows() {
    assertAllEqual(0.1f, Worker.normalize(filled(0.01f, 100), -20f, 20f, 0.99f));
  }

  @Test void normalizeStopsAmplifyingAtTheGainCap() {
    assertAllEqual(0.03f, Worker.normalize(filled(0.003f, 100), -20f, 20f, 0.99f));
    assertAllEqual(0.01f * (float) Math.pow(10, 6 / 20.0), Worker.normalize(filled(0.01f, 100), -20f, 6f, 0.99f));
  }

  @Test void normalizeKeepsThePeakAtOrBelowTheLimit() {
    float[] x = new float[100];
    x[0] = 0.5f;
    float[] out = Worker.normalize(x, -20f, 20f, 0.99f);
    assertEquals(0.99f, out[0], TOLERANCE);
    float max = 0f;
    for (float v : out) max = Math.max(max, Math.abs(v));
    assertTrue(max <= 0.99f + TOLERANCE, "peak " + max);
  }

  @Test void normalizeAmplifiesAudioJustAboveTheSilenceGuard() {
    assertAllEqual(0.01953125f, Worker.normalize(filled(0.001953125f, 100), -20f, 20f, 0.99f));
  }

  @Test void normalizeLeavesAudioJustBelowTheSilenceGuardUntouched() {
    float[] quiet = filled(0.0009765625f, 100);
    assertSame(quiet, Worker.normalize(quiet, -20f, 20f, 0.99f));
  }

  @Test void normalizeReturnsSilenceAndEmptyAudioAsIs() {
    float[] silence = new float[100];
    assertSame(silence, Worker.normalize(silence, -20f, 20f, 0.99f));
    float[] empty = new float[0];
    assertSame(empty, Worker.normalize(empty, -20f, 20f, 0.99f));
  }

  @Test void normalizeDoesNotModifyItsInput() {
    float[] x = filled(0.5f, 100);
    Worker.normalize(x, -20f, 20f, 0.99f);
    assertArrayEquals(filled(0.5f, 100), x);
  }

  @Test void preprocessOnlyPadsEnginesWithoutNormalization() {
    var c = of(Map.of("ASR_PAD_MS", "2", "ASR_NORMALIZE_ENGINES", "reazon-ja"));
    float[] x = filled(0.5f, 4);
    assertArrayEquals(Worker.pad(x, 2), Worker.preprocess(x, c, "sensevoice"));
  }

  @Test void preprocessNormalizesBeforePaddingForEnginesWithNormalization() {
    var c = of(Map.of("ASR_PAD_MS", "1", "ASR_NORMALIZE_ENGINES", "reazon-ja"));
    float[] out = Worker.preprocess(filled(0.5f, 4), c, "reazon-ja");
    assertEquals(4 + 2 * 16, out.length);
    for (int i = 0; i < 16; i++) assertEquals(0f, out[i], "leading pad " + i);
    for (int i = 16; i < 20; i++) assertEquals(0.1f, out[i], TOLERANCE, "sample " + i);
    for (int i = 20; i < out.length; i++) assertEquals(0f, out[i], "trailing pad " + i);
  }

  @Test void preprocessPadsEachEngineWithItsOwnLength() {
    var c = of(Map.of("ASR_PAD_MS", "300", "ASR_PAD_MS_BY_ENGINE", "reazon-ja:600"));
    float[] x = filled(0.5f, 4);
    assertEquals(4 + 2 * 9600, Worker.preprocess(x, c, "reazon-ja").length);
    assertEquals(4 + 2 * 4800, Worker.preprocess(x, c, "sensevoice").length);
  }

  @Test void preprocessWithoutNewSettingsMatchesThePlainPad() {
    var c = of(Map.of("ASR_PAD_MS", "300"));
    float[] x = filled(0.25f, 8);
    assertArrayEquals(Worker.pad(x, 300), Worker.preprocess(x, c, "sensevoice"));
    assertArrayEquals(Worker.pad(x, 300), Worker.preprocess(x, c, "reazon-ja"));
  }
}

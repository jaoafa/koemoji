package koemoji.capture;

import static org.junit.jupiter.api.Assertions.*;

import koemoji.Config;
import koemoji.queue.Db;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserPipelineBoundaryTest {
  private static final int WINDOWS_TO_START = 3;
  private static final int WINDOW_BYTES = UserPipeline.WIN * 2;
  private static final int END_SILENCE_WINDOWS = 32;
  private static final int LEADING_NON_SPEECH_WINDOWS = 25;

  private record Fixture(UserPipeline pipeline, Db db, List<String> utterances, Path audioDir) implements AutoCloseable {
    @Override public void close() { pipeline.close(); }
  }

  private static final class ScriptedVad implements UserPipeline.VadDetector {
    private final int leadingNonSpeech;
    private int calls;
    ScriptedVad(int leadingNonSpeech) { this.leadingNonSpeech = leadingNonSpeech; }
    @Override public float compute(float[] samples) {
      int n = calls++;
      return n >= leadingNonSpeech && n < leadingNonSpeech + WINDOWS_TO_START ? 1f : 0f;
    }
    @Override public void reset() {}
    @Override public void release() {}
  }

  private static Fixture fixture(Map<String, String> env) throws Exception {
    return fixture(env, 0);
  }

  private static Fixture fixture(Map<String, String> env, int leadingNonSpeech) throws Exception {
    var tmp = Files.createTempDirectory("cwup-boundary");
    var values = new HashMap<>(env);
    values.put("AUDIO_DIR", tmp.resolve("audio").toString());
    values.put("QUEUE_DB", tmp.resolve("q.db").toString());
    var config = Config.of(values::get);
    Files.createDirectories(config.audioDir());
    var db = new Db(config);
    var utterances = new ArrayList<String>();
    var detector = new ScriptedVad(leadingNonSpeech);
    var pipeline = new UserPipeline(config, db, "u", utterances::add, detector);
    return new Fixture(pipeline, db, utterances, config.audioDir());
  }

  private static long prerollWindows(Map<String, String> env) throws Exception {
    try (var f = fixture(env, LEADING_NON_SPEECH_WINDOWS)) {
      for (int i = 0; i < LEADING_NON_SPEECH_WINDOWS + WINDOWS_TO_START + END_SILENCE_WINDOWS; i++) feedWindow(f.pipeline());
      assertEquals(1, f.utterances().size());
      long bytes = Files.size(f.audioDir().resolve(f.utterances().get(0) + ".pcm"));
      assertEquals(0, bytes % WINDOW_BYTES);
      return bytes / WINDOW_BYTES - END_SILENCE_WINDOWS;
    }
  }

  private static void feedWindow(UserPipeline pipeline) {
    var pcm = ByteBuffer.allocate(UserPipeline.WIN * 3 * 4).order(ByteOrder.BIG_ENDIAN);
    for (int i = 0; i < UserPipeline.WIN; i++)
      for (int k = 0; k < 6; k++) pcm.putShort((short) 1000);
    pipeline.accept(pcm.array());
  }

  private static void feedSilence(Fixture fixture, int windows) {
    for (int i = 0; i < WINDOWS_TO_START + windows; i++) feedWindow(fixture.pipeline());
  }

  @Test void defaultThresholdKeepsUtteranceOpenAt992msAndFinalizesAt1024ms() throws Exception {
    try (var f = fixture(Map.of())) {
      feedSilence(f, 31);
      assertTrue(f.utterances().isEmpty());
      assertTrue(f.db().counts().isEmpty());
      feedWindow(f.pipeline());
      assertEquals(1, f.utterances().size());
      assertTrue(f.db().counts().getOrDefault("sensevoice|queued", 0L) > 0);
    }
  }

  @Test void explicit700msOverrideKeepsUtteranceOpenAt672msAndFinalizesAt704ms() throws Exception {
    try (var f = fixture(Map.of("VAD_END_SILENCE_MS", "700"))) {
      feedSilence(f, 21);
      assertTrue(f.utterances().isEmpty());
      assertTrue(f.db().counts().isEmpty());
      feedWindow(f.pipeline());
      assertEquals(1, f.utterances().size());
      assertTrue(f.db().counts().getOrDefault("sensevoice|queued", 0L) > 0);
    }
  }

  @Test void default320msPrerollKeepsTenWindowsAtTheStartOfTheUtterance() throws Exception {
    assertEquals(10, prerollWindows(Map.of()));
  }

  @Test void explicit500msPrerollKeepsFifteenWindowsAtTheStartOfTheUtterance() throws Exception {
    assertEquals(15, prerollWindows(Map.of("VAD_PREROLL_MS", "500")));
  }

  @Test void explicit640msPrerollKeepsTwentyWindowsAtTheStartOfTheUtterance() throws Exception {
    assertEquals(20, prerollWindows(Map.of("VAD_PREROLL_MS", "640")));
  }

  @Test void prerollShorterThanVadStartIsRaisedToTheVadStartWindows() throws Exception {
    assertEquals(3, prerollWindows(Map.of("VAD_PREROLL_MS", "32", "VAD_START_MS", "96")));
  }
}

package koemoji.capture;

import static org.junit.jupiter.api.Assertions.*;

import koemoji.Config;
import koemoji.queue.Db;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** VAD とジョブの登録だけを確かめる(ASR は使わない)。data/models に Silero のモデルと音声入りの wav が必要。 */
class UserPipelineTest {
  static final Path WAV = Path.of("data/models/test_silero_vad.wav");
  Db db;
  UserPipeline p;
  final List<String> utts = new ArrayList<>();

  @BeforeEach void setUp() throws Exception {
    Assumptions.assumeTrue(Files.exists(WAV) && Files.exists(Path.of("data/models/silero_vad.onnx")), "models not downloaded");
    var tmp = Files.createTempDirectory("cwup");
    var env = Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", tmp.resolve("q.db").toString(), "ASR_ENGINES", "a,b");
    var c = Config.of(env::get);
    Files.createDirectories(c.audioDir());
    db = new Db(c);
    p = new UserPipeline(c, db, "u", utts::add);
  }

  /** 16 kHz モノラルのサンプルを、JDA が渡す形式(48 kHz ステレオ big-endian PCM)の 20 ms 単位で流し込む。 */
  private void feed(short[] samples) throws Exception {
    var chunk = ByteBuffer.allocate(320 * 3 * 4);
    for (int off = 0; off + 320 <= samples.length; off += 320) {
      chunk.clear();
      for (int i = 0; i < 320; i++) for (int k = 0; k < 6; k++) chunk.putShort(samples[off + i]);
      p.accept(chunk.array().clone());
      p.tick();
      Thread.sleep(3);
    }
  }

  private static short[] wav() throws Exception {
    byte[] b = Files.readAllBytes(WAV);
    var s = ByteBuffer.wrap(b, 44, b.length - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    short[] out = new short[s.remaining()];
    s.get(out);
    return out;
  }

  @Test void silenceStartsNoUtterance() throws Exception {
    feed(new short[16000 * 3]);
    p.close();
    assertTrue(utts.isEmpty());
    assertTrue(db.counts().isEmpty());
  }

  @Test void speechBecomesUtterancesWithOneFinalJobPerEngineAtTheSameRevision() throws Exception {
    feed(wav());
    p.close();
    assertFalse(utts.isEmpty());
    var a = db.claim("w", "a").orElseThrow();
    var b = db.claim("w", "b").orElseThrow();
    assertTrue(a.isFinal() && b.isFinal());
    assertEquals(a.revision(), b.revision());
    assertEquals(a.utteranceId(), b.utteranceId());
    assertTrue(utts.contains(a.utteranceId()));
    assertTrue(Files.size(Path.of(a.audioPath())) >= a.snapshotBytes() && a.snapshotBytes() > 0);
  }

  @Test void closingMidUtteranceStillFinishesIt() throws Exception {
    short[] speech = wav();
    feed(java.util.Arrays.copyOf(speech, 16000 * 4));
    p.close();
    assertTrue(db.counts().getOrDefault("a|queued", 0L) > 0);
    assertTrue(p.idle(-1));
  }

  @Test void unwritableAudioDirDropsUtterancesWithoutBreakingThePipeline() throws Exception {
    p.close();
    var env = Map.of("AUDIO_DIR", Files.createTempDirectory("cwup").resolve("missing").toString(), "ASR_ENGINES", "a");
    p = new UserPipeline(Config.of(env::get), db, "u", utts::add);
    assertDoesNotThrow(() -> feed(wav()));
    p.close();
    assertTrue(utts.isEmpty());
    assertTrue(db.counts().isEmpty());
  }

  @Test void audioArrivingAfterCloseIsIgnored() throws Exception {
    p.close();
    assertDoesNotThrow(() -> feed(java.util.Arrays.copyOf(wav(), 16000)));
    assertTrue(utts.isEmpty());
  }
}

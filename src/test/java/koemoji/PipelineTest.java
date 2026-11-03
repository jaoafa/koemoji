package koemoji;

import koemoji.queue.Db;
import koemoji.capture.UserPipeline;
import koemoji.asr.Worker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** Discord を使わない結合テスト。wav から UserPipeline(VAD、partial/final のジョブ化)、Worker(SenseVoice)を通して結果を確かめる。 */
class PipelineTest {
  @Test void wavBecomesPartialsAndFinal() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(Path.of("data/models/sensevoice")) && Files.exists(Path.of("data/models/test_silero_vad.wav")), "models not downloaded");
    Path tmp = Files.createTempDirectory("cw");
    var env = java.util.Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", tmp.resolve("q.db").toString(),
        "ASR_ENGINES", "sensevoice", "ASR_THREADS", "2", "ASR_LANGUAGE", "", "PARTIAL_INTERVAL_MS", "500",
        "RETRY_MAX", "2", "RETRY_BACKOFF_MS", "100");
    var c = Config.of(env::get);
    var db = new Db(c);
    Worker.start(c, db);
    var utts = new java.util.ArrayList<String>();
    var p = new UserPipeline(c, db, "test", utts::add);

    byte[] wav = Files.readAllBytes(Path.of("data/models/test_silero_vad.wav"));
    var s = ByteBuffer.wrap(wav, 44, wav.length - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    var chunk = ByteBuffer.allocate(320 * 3 * 4);  // JDA が渡す形式の、48 kHz ステレオ big-endian で 20 ms 分
    while (s.remaining() >= 320) {
      chunk.clear();
      for (int i = 0; i < 320; i++) { short v = s.get(); for (int k = 0; k < 6; k++) chunk.putShort(v); }
      p.accept(chunk.array().clone());
      p.tick();
      Thread.sleep(3);
    }
    p.close();
    assertFalse(utts.isEmpty(), "VAD found no utterance");

    var texts = new java.util.HashMap<String, String>();  // final のみ
    long deadline = System.currentTimeMillis() + 120_000;
    int finals = 0;
    while (finals < utts.size() && System.currentTimeMillis() < deadline) {
      Thread.sleep(500);
      for (var d : db.pollDone()) if (d.isFinal()) { texts.put(d.utteranceId(), d.text()); finals++; db.markApplied(java.util.List.of(d.id())); }
    }
    assertEquals(utts.size(), texts.size(), "every utterance must get a final");
    assertTrue(texts.values().stream().anyMatch(t -> !t.isBlank()), "no text recognized: " + texts);
    System.out.println("RESULT " + texts);
  }
}

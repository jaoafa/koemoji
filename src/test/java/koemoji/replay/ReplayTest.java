package koemoji.replay;

import koemoji.Config;
import koemoji.asr.AsrEngine;
import koemoji.queue.Db;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayTest {
  /** 0.25 / 0.5 / 0.75 を s16le で厳密に表せる値。 */
  static final short QUARTER = 8192, HALF = 16384, THREE_QUARTERS = 24576;

  @TempDir Path tmp;
  Path audio, out;
  ByteArrayOutputStream stdout, stderr;

  /** パディングで長さ、正規化で最大振幅が変わるので、条件ごとに違う文字列を返す。 */
  static final class DummyEngine implements AsrEngine {
    final List<Integer> seen = new ArrayList<>();
    boolean closed;
    final boolean failing;

    DummyEngine(boolean failing) { this.failing = failing; }

    @Override public Capabilities capabilities() { return new Capabilities("dummy", false, 30, false); }
    @Override public Result recognizePartial(float[] samples) { throw new UnsupportedOperationException(); }
    @Override public Result recognizeFinal(float[] samples) {
      if (failing) throw new IllegalStateException("boom");
      seen.add(samples.length);
      float max = 0;
      for (float v : samples) max = Math.max(max, Math.abs(v));
      return new Result(max == 0.75f ? "。" : "n" + samples.length + "m" + Math.round(max * 100), 0, 0);
    }
    @Override public void close() { closed = true; }
  }

  static final class Factory implements Replay.EngineFactory {
    final List<DummyEngine> engines = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<Config> configs = new ArrayList<>();
    final boolean failing;

    Factory(boolean failing) { this.failing = failing; }

    @Override public AsrEngine create(String engine, Config c) {
      names.add(engine);
      configs.add(c);
      engines.add(new DummyEngine(failing));
      return engines.get(engines.size() - 1);
    }
  }

  @BeforeEach void setUp() throws Exception {
    audio = Files.createDirectory(tmp.resolve("audio"));
    out = tmp.resolve("out");
    stdout = new ByteArrayOutputStream();
    stderr = new ByteArrayOutputStream();
  }

  private static byte[] pcm(int samples, short value) {
    var b = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) b.putShort(value);
    return b.array();
  }

  private void write(String id, byte[] bytes) throws Exception { Files.write(audio.resolve(id + ".pcm"), bytes); }

  private int run(Replay.EngineFactory factory, Map<String, String> env, String... args) throws Exception {
    var all = new ArrayList<>(List.of("--audio-dir", audio.toString(), "--out", out.toString()));
    all.addAll(List.of(args));
    return Replay.run(all.toArray(String[]::new), env::get, factory, new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
  }

  private String stdout() { return stdout.toString(StandardCharsets.UTF_8); }
  private String stderr() { return stderr.toString(StandardCharsets.UTF_8); }
  private String read(String file) throws Exception { return Files.readString(out.resolve(file)); }

  private void threeUtterances() throws Exception {
    write("u1", pcm(32, QUARTER));
    write("u2", pcm(32, HALF));
    write("u3", pcm(32, THREE_QUARTERS));
  }

  @Test void aggregatesTwoPaddingConditionsExactly() throws Exception {
    threeUtterances();
    var factory = new Factory(false);
    assertEquals(0, run(factory, Map.of(), "--engines", "sensevoice", "--pad-ms", "0,100", "--normalize", "off", "--top", "2"), stderr());
    assertEquals(read("summary.md"), stdout());
    assertEquals("""
        # replay summary

        - audio dir: %s
        - utterances: targets=3, replayed=3, skipped=0 (missing file=0, empty=0, odd bytes=0, over snapshot=0, invalid id=0)

        ## Conditions

        | # | condition |
        |---|---|
        | 1 | sensevoice/pad0/normoff |
        | 2 | sensevoice/pad100/normoff |

        ## Per condition

        | condition | values | empty | chars | CER %% | CER utterances |
        |---|---|---|---|---|---|
        | sensevoice/pad0/normoff | 3 | 1 | 12 | - | - |
        | sensevoice/pad100/normoff | 3 | 1 | 16 | - | - |

        ## Pairwise disagreement

        | condition A | condition B | mean disagreement | utterances |
        |---|---|---|---|
        | sensevoice/pad0/normoff | sensevoice/pad100/normoff | 0.1667 | 3 |

        ## Top 2 utterances by disagreement

        | utterance_id | max disagreement | duration ms |
        |---|---|---|
        | u1 | 0.2500 | 2 |
        | u2 | 0.2500 | 2 |
        """.formatted(audio), stdout());
    assertEquals("""
        utterance_id\tduration_ms\tcondition\ttext
        u1\t2\tsensevoice/pad0/normoff\tn32m25
        u1\t2\tsensevoice/pad100/normoff\tn3232m25
        u2\t2\tsensevoice/pad0/normoff\tn32m50
        u2\t2\tsensevoice/pad100/normoff\tn3232m50
        u3\t2\tsensevoice/pad0/normoff\t。
        u3\t2\tsensevoice/pad100/normoff\t。
        """, read("utterances.tsv"));
    assertEquals("""
        utterance_id\tmax_disagreement\tcondition\ttext
        u1\t0.2500\tsensevoice/pad0/normoff\tn32m25
        u1\t0.2500\tsensevoice/pad100/normoff\tn3232m25
        u2\t0.2500\tsensevoice/pad0/normoff\tn32m50
        u2\t0.2500\tsensevoice/pad100/normoff\tn3232m50
        """, read("disagreements.tsv"));
    assertFalse(stdout().contains("n32m25"));
    assertTrue(factory.engines.get(0).closed);
  }

  @Test void normalizationChangesWhatTheEngineHears() throws Exception {
    write("u1", pcm(32, QUARTER));
    var env = Map.of("ASR_NORMALIZE_TARGET_DBFS", "0", "ASR_NORMALIZE_MAX_GAIN_DB", "60", "ASR_NORMALIZE_PEAK", "0.5");
    assertEquals(0, run(new Factory(false), env, "--engines", "sensevoice", "--pad-ms", "0", "--normalize", "off,on"), stderr());
    assertEquals("""
        utterance_id\tduration_ms\tcondition\ttext
        u1\t2\tsensevoice/pad0/normoff\tn32m25
        u1\t2\tsensevoice/pad0/normon\tn32m50
        """, read("utterances.tsv"));
    assertTrue(stdout().contains("| sensevoice/pad0/normoff | sensevoice/pad0/normon | 0.3333 | 1 |"), stdout());
  }

  @Test void eachEngineIsBuiltOnceWithItsOwnWorkerConfigAndClosed() throws Exception {
    write("u1", pcm(32, QUARTER));
    var factory = new Factory(false);
    assertEquals(0, run(factory, Map.of("MODE", "capture", "WORKER_ENGINES", "sensevoice:2"), "--engines", "sensevoice,reazon-ja", "--pad-ms", "0"),
        stderr());
    assertEquals(List.of("sensevoice", "reazon-ja"), factory.names);
    assertEquals(List.of(List.of("sensevoice"), List.of("reazon-ja")), factory.configs.stream().map(Config::workerEngines).toList());
    assertTrue(factory.configs.stream().allMatch(Config::worker));
    assertTrue(factory.engines.stream().allMatch(e -> e.closed));
    assertTrue(stdout().contains("| sensevoice/pad0/normoff | reazon-ja/pad0/normoff | 0.0000 | 1 |"), stdout());
  }

  @Test void snapshotBytesSavedTextAndSkipReasonsFromTheQueue() throws Exception {
    var queueDb = tmp.resolve("queue.db");
    var db = new Db(Config.of(Map.of("AUDIO_DIR", audio.toString(), "QUEUE_DB", queueDb.toString())::get));
    byte[] snapshotLonger = new byte[101];
    System.arraycopy(pcm(32, QUARTER), 0, snapshotLonger, 0, 64);
    java.util.Arrays.fill(snapshotLonger, 64, 101, (byte) 0x7F);
    write("a-ok", pcm(32, QUARTER));
    write("b-trunc", snapshotLonger);
    write("d-over", pcm(32, QUARTER));
    write("e-zero", pcm(32, QUARTER));
    write("f-odd", pcm(32, QUARTER));
    try (Connection keepWal = DriverManager.getConnection("jdbc:sqlite:" + queueDb)) {
      keepWal.createStatement().execute("SELECT count(*) FROM jobs");
      db.enqueue("a-ok", 1, true, "x", 64, "sensevoice");
      db.complete(db.claim("w", "sensevoice").orElseThrow().id(), "w", "n32m25");
      db.enqueue("b-trunc", 1, true, "x", 64, "sensevoice");
      db.complete(db.claim("w", "sensevoice").orElseThrow().id(), "w", "ほげ");
      db.enqueue("c-missing", 1, true, "x", 64, "sensevoice");
      db.enqueue("d-over", 1, true, "x", 100, "sensevoice");
      db.enqueue("e-zero", 1, true, "x", 0, "sensevoice");
      db.enqueue("f-odd", 1, true, "x", 3, "sensevoice");
      db.enqueue("g..h", 1, true, "x", 64, "sensevoice");
      Path wal = Path.of(queueDb + "-wal");
      assertTrue(Files.exists(wal) && Files.size(wal) > 0);
      byte[] dbBefore = Files.readAllBytes(queueDb), walBefore = Files.readAllBytes(wal);
      var factory = new Factory(false);
      assertEquals(0, run(factory, Map.of(), "--engines", "sensevoice", "--pad-ms", "0", "--normalize", "off", "--queue-db", queueDb.toString()),
          stderr());
      assertEquals(List.of(32, 32), factory.engines.get(0).seen);
      assertArrayEquals(dbBefore, Files.readAllBytes(queueDb));
      assertArrayEquals(walBefore, Files.readAllBytes(wal));
    }
    var summary = stdout();
    assertTrue(summary.contains(
        "- utterances: targets=7, replayed=2, skipped=5 (missing file=1, empty=1, odd bytes=1, over snapshot=1, invalid id=1)"), summary);
    assertTrue(summary.contains("| sensevoice/pad0/normoff | 2 | 0 | 12 | - | - |"), summary);
    assertTrue(summary.contains("| saved/sensevoice | 2 | 0 | 8 | - | - |"), summary);
    assertTrue(summary.contains("| sensevoice/pad0/normoff | saved/sensevoice | 0.5000 | 2 |"), summary);
    assertTrue(summary.contains("| b-trunc | 1.0000 | 2 |"), summary);
  }

  @Test void aMissingConditionDropsOutOfTheComparison() throws Exception {
    var queueDb = tmp.resolve("queue.db");
    var db = new Db(Config.of(Map.of("AUDIO_DIR", audio.toString(), "QUEUE_DB", queueDb.toString())::get));
    write("a", pcm(32, QUARTER));
    write("b", pcm(32, QUARTER));
    db.enqueue("a", 1, true, "x", 64, "sensevoice");
    db.complete(db.claim("w", "sensevoice").orElseThrow().id(), "w", "n32m25");
    db.enqueue("b", 1, true, "x", 64, "sensevoice");
    assertEquals(0, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--pad-ms", "0", "--normalize", "off", "--queue-db",
        queueDb.toString()), stderr());
    assertTrue(stdout().contains("| saved/sensevoice | 1 | 0 | 6 | - | - |"), stdout());
    assertTrue(stdout().contains("| sensevoice/pad0/normoff | saved/sensevoice | 0.0000 | 1 |"), stdout());
  }

  @Test void referenceTextGivesExactCer() throws Exception {
    threeUtterances();
    Files.writeString(tmp.resolve("ref.tsv"), "u1\tn32m25\n\nu2\tN32M51\nzzz\tunused\nu3\t\n");
    assertEquals(0, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--pad-ms", "0,100", "--normalize", "off", "--reference",
        tmp.resolve("ref.tsv").toString()), stderr());
    assertTrue(stdout().contains("| sensevoice/pad0/normoff | 3 | 1 | 12 | 8.33 | 2 |"), stdout());
    assertTrue(stdout().contains("| sensevoice/pad100/normoff | 3 | 1 | 16 | 41.67 | 2 |"), stdout());
  }

  @Test void malformedReferenceIsRejectedWithTheLineNumber() throws Exception {
    threeUtterances();
    Files.writeString(tmp.resolve("ref.tsv"), "u1\ta\nnotab\n");
    assertEquals(2, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--reference", tmp.resolve("ref.tsv").toString()));
    assertTrue(stderr().contains("line 2"), stderr());
    stderr.reset();
    Files.writeString(tmp.resolve("ref.tsv"), "u1\ta\nu1\tb\n");
    assertEquals(2, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--reference", tmp.resolve("ref.tsv").toString()));
    assertTrue(stderr().contains("line 2") && stderr().contains("u1") && stderr().contains("line 1"), stderr());
    assertFalse(Files.exists(out));
  }

  @Test void limitAndTopCutTheWork() throws Exception {
    threeUtterances();
    assertEquals(0, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--pad-ms", "0,100", "--normalize", "off", "--limit", "2",
        "--top", "1"), stderr());
    var summary = stdout();
    assertTrue(summary.contains("targets=2, replayed=2"), summary);
    assertTrue(summary.contains("## Top 1 utterances by disagreement"), summary);
    assertEquals("""
        utterance_id\tmax_disagreement\tcondition\ttext
        u1\t0.2500\tsensevoice/pad0/normoff\tn32m25
        u1\t0.2500\tsensevoice/pad100/normoff\tn3232m25
        """, read("disagreements.tsv"));
  }

  @Test void skipReasonsWithoutAQueueUseTheFileSize() throws Exception {
    write("ok", pcm(32, QUARTER));
    write("empty", new byte[0]);
    write("odd", new byte[3]);
    write("..", pcm(2, QUARTER));
    assertEquals(0, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--pad-ms", "0"), stderr());
    assertTrue(stdout().contains("targets=4, replayed=1, skipped=3 (missing file=0, empty=1, odd bytes=1, over snapshot=0, invalid id=1)"),
        stdout());
  }

  @Test void nothingToReplayIsAnInputError() throws Exception {
    write("empty", new byte[0]);
    assertEquals(2, run(new Factory(false), Map.of(), "--engines", "sensevoice"));
    assertTrue(stderr().contains("No utterance can be replayed: targets=1 (missing file=0, empty=1,"), stderr());
    assertFalse(Files.exists(out));  // 認識に進む前の入力エラーでは、出力先を作らない
  }

  @Test void anEngineFailureStopsTheRunAndNamesTheUtteranceAndCondition() throws Exception {
    threeUtterances();
    assertEquals(1, run(new Factory(true), Map.of(), "--engines", "sensevoice", "--pad-ms", "0", "--normalize", "off"));
    assertTrue(stderr().contains("u1") && stderr().contains("sensevoice/pad0/normoff") && stderr().contains("boom"), stderr());
    try (Stream<Path> files = Files.list(out)) { assertEquals(List.of(), files.toList()); }
  }

  @Test void anUnwritableOutputDirectoryFailsBeforeRecognition() throws Exception {
    threeUtterances();
    var blocker = Files.writeString(tmp.resolve("blocker"), "x");
    out = blocker.resolve("out");
    var factory = new Factory(false);
    assertEquals(1, run(factory, Map.of(), "--engines", "sensevoice"));
    assertEquals(List.of(), factory.names);
  }

  @Test void aReferenceThatIsNotUtf8IsAnInputError() throws Exception {
    threeUtterances();
    Files.write(tmp.resolve("ref.tsv"), new byte[] {'u', '1', '\t', (byte) 0xFF, (byte) 0xFE, '\n'});
    assertEquals(2, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--reference", tmp.resolve("ref.tsv").toString()));
    assertTrue(stderr().contains("--reference must be UTF-8") && stderr().contains("ref.tsv"), stderr());
  }

  @Test void badArgumentsExitWithTwoAndWriteNothing() throws Exception {
    threeUtterances();
    assertEquals(2, run(new Factory(false), Map.of(), "--engines", "sensevoice", "--bogus", "1"));
    assertTrue(stderr().contains("--bogus"), stderr());
    Files.createDirectory(out);
    Files.writeString(out.resolve("old.txt"), "keep");
    stderr.reset();
    assertEquals(2, run(new Factory(false), Map.of(), "--engines", "sensevoice"));
    assertTrue(stderr().contains("--out"), stderr());
    try (Stream<Path> files = Files.list(out)) { assertEquals(List.of("old.txt"), files.map(p -> p.getFileName().toString()).toList()); }
  }
}

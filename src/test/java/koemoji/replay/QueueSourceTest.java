package koemoji.replay;

import koemoji.Config;
import koemoji.queue.Db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueSourceTest {
  @TempDir Path tmp;
  Path queueDb;
  Db db;
  Connection keepWal;

  @BeforeEach void setUp() throws Exception {
    queueDb = tmp.resolve("queue.db");
    db = new Db(Config.of(Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", queueDb.toString())::get));
    keepWal = DriverManager.getConnection("jdbc:sqlite:" + queueDb);
    keepWal.createStatement().execute("SELECT count(*) FROM jobs");
  }

  @AfterEach void tearDown() throws Exception { keepWal.close(); }

  private void finish(String utt, String engine, long bytes, String text) {
    db.enqueue(utt, 1, true, "/x/" + utt, bytes, engine);
    var job = db.claim("w", engine).orElseThrow();
    db.complete(job.id(), "w", text);
  }

  @Test void returnsOnlyUtterancesWithAFinalInAscendingOrder() {
    finish("b", "e", 10, "x");
    db.enqueue("c", 1, false, "/x/c", 10, "e");
    finish("a", "e", 20, "y");
    var targets = QueueSource.read(queueDb, List.of("e"));
    assertEquals(List.of("a", "b"), targets.stream().map(QueueSource.Target::utteranceId).toList());
  }

  @Test void snapshotBytesComesFromTheEarliestFinal() {
    finish("a", "e", 10, "x");
    finish("a", "e", 99, "y");
    assertEquals(10, QueueSource.read(queueDb, List.of("e")).get(0).snapshotBytes());
  }

  @Test void savedTextIsTheLatestCompletedFinalPerRequestedEngine() {
    finish("a", "e", 10, "first");
    finish("a", "e", 10, "second");
    finish("a", "other", 10, "ignored");
    db.enqueue("a", 1, true, "/x/a", 10, "f");
    db.fail(db.claim("w", "f").orElseThrow(), "w", "boom", 0, 0);
    var t = QueueSource.read(queueDb, List.of("e", "f")).get(0);
    assertEquals(Map.of("e", "second"), t.saved());
  }

  @Test void aCompletedRowWithNullTextBecomesEmpty() {
    finish("a", "e", 10, null);
    assertEquals(Map.of("e", ""), QueueSource.read(queueDb, List.of("e")).get(0).saved());
  }

  @Test void readingLeavesQueueDbAndWalUnchanged() throws Exception {
    finish("a", "e", 10, "x");
    Path wal = Path.of(queueDb + "-wal");
    assertTrue(Files.exists(wal) && Files.size(wal) > 0);
    byte[] before = Files.readAllBytes(queueDb), walBefore = Files.readAllBytes(wal);
    QueueSource.read(queueDb, List.of("e"));
    assertArrayEquals(before, Files.readAllBytes(queueDb));
    assertArrayEquals(walBefore, Files.readAllBytes(wal));
  }

  @Test void aFileWithoutJobsTableIsRejectedAndUntouched() throws Exception {
    Path other = tmp.resolve("other.db");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + other)) { c.createStatement().execute("CREATE TABLE t(x)"); }
    byte[] before = Files.readAllBytes(other);
    var e = assertThrows(IllegalStateException.class, () -> QueueSource.read(other, List.of("e")));
    assertTrue(e.getMessage().contains(other.toString()), e.getMessage());
    assertArrayEquals(before, Files.readAllBytes(other));
  }

  @Test void aMissingFileIsRejectedAndNotCreated() {
    Path missing = tmp.resolve("missing.db");
    var e = assertThrows(IllegalStateException.class, () -> QueueSource.read(missing, List.of("e")));
    assertTrue(e.getMessage().contains(missing.toString()), e.getMessage());
    assertFalse(Files.exists(missing));
  }
}

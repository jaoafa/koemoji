package koemoji.queue;

import koemoji.Config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DbTest {
  Db db;
  Path queueDb;

  @BeforeEach void setUp() throws Exception {
    var tmp = Files.createTempDirectory("cwdb");
    queueDb = tmp.resolve("q.db");
    var env = Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", queueDb.toString());
    db = new Db(Config.of(env::get));
  }

  private void enqueue(String utt, int rev, boolean fin, String engine) { db.enqueue(utt, rev, fin, "/x/" + utt, 100, engine); }

  @Test void finalIsClaimedBeforePartialsThenNewestPartialFirst() {
    enqueue("u1", 1, false, "e");
    enqueue("u2", 1, false, "e");
    enqueue("u3", 1, true, "e");
    assertTrue(db.claim("w", "e").get().isFinal());
    assertEquals("u2", db.claim("w", "e").get().utteranceId());
    assertEquals("u1", db.claim("w", "e").get().utteranceId());
    assertTrue(db.claim("w", "e").isEmpty());
  }

  @Test void newerJobSupersedesQueuedPartialsOfSameUtteranceOnly() {
    enqueue("u1", 1, false, "e");
    enqueue("u1", 2, false, "e");
    enqueue("u2", 1, false, "e");
    enqueue("u1", 3, true, "e");
    var revs = new java.util.ArrayList<String>();
    db.claim("w", "e").ifPresent(j -> revs.add(j.utteranceId() + j.revision()));
    db.claim("w", "e").ifPresent(j -> revs.add(j.utteranceId() + j.revision()));
    assertTrue(db.claim("w", "e").isEmpty());
    assertEquals(java.util.List.of("u13", "u21"), revs);
  }

  @Test void claimOnlyReturnsJobsOfTheWorkersEngine() {
    enqueue("u1", 1, true, "a");
    assertTrue(db.claim("w", "b").isEmpty());
    assertTrue(db.claim("w", "a").isPresent());
  }

  @Test void claimingTwiceNeverReturnsTheSameJob() {
    enqueue("u1", 1, true, "e");
    assertTrue(db.claim("w1", "e").isPresent());
    assertTrue(db.claim("w2", "e").isEmpty());
  }

  @Test void finalRetriesUntilLimitThenFailsWhilePartialFailsImmediately() {
    enqueue("u1", 1, true, "e");
    db.fail(db.claim("w", "e").get(), "w", "boom", 1, 0);
    var retried = db.claim("w", "e").get();
    assertEquals(1, retried.retryCount());
    db.fail(retried, "w", "boom", 1, 0);
    assertTrue(db.claim("w", "e").isEmpty());
    assertEquals("failed", db.pollDone().get(0).status());

    enqueue("u2", 1, false, "e");
    db.fail(db.claim("w", "e").get(), "w", "boom", 5, 0);
    assertTrue(db.claim("w", "e").isEmpty());
  }

  @Test void backoffDelaysTheRetry() {
    enqueue("u1", 1, true, "e");
    db.fail(db.claim("w", "e").get(), "w", "boom", 3, 60_000);
    assertTrue(db.claim("w", "e").isEmpty());
  }

  @Test void stuckProcessingJobsAreRequeued() {
    enqueue("u1", 1, true, "e");
    db.claim("dead-worker", "e");
    assertEquals(1, db.requeueStale(-1000, 5));
    assertTrue(db.claim("w", "e").isPresent());
  }

  @Test void completionByAReapedWorkerIsIgnored() {
    enqueue("u1", 1, true, "e");
    var j = db.claim("w1", "e").get();
    db.requeueStale(-1000, 5);
    var again = db.claim("w2", "e").get();
    db.complete(j.id(), "w1", "late");
    assertTrue(db.pollDone().isEmpty());
    db.complete(again.id(), "w2", "ok");
    assertEquals("ok", db.pollDone().get(0).text());
  }

  @Test void audioExpiresOnlyAfterEveryEnginesFinalSettled() throws Exception {
    enqueue("u1", 1, true, "a");
    enqueue("u1", 1, true, "b");
    db.complete(db.claim("w", "a").get().id(), "w", "x");
    Thread.sleep(5);
    assertTrue(db.expired(0, 0).isEmpty());
    db.complete(db.claim("w", "b").get().id(), "w", "y");
    Thread.sleep(5);
    assertEquals(1, db.expired(0, 0).size());
    assertTrue(db.expired(60_000, 60_000).isEmpty());
  }

  @Test void utterancesThatNeverGotAFinalAreReclaimedAfterTheFailedTtl() throws Exception {
    enqueue("u1", 1, false, "a");
    db.complete(db.claim("w", "a").get().id(), "w", "x");
    Thread.sleep(5);
    assertTrue(db.expired(60_000, 60_000).isEmpty());  // まだ新しい: 発話が継続中かもしれない
    assertEquals(1, db.expired(0, 0).size());
  }

  @Test void appliedResultsAreNotPolledAgain() {
    enqueue("u1", 1, true, "e");
    db.complete(db.claim("w", "e").get().id(), "w", "x");
    var done = db.pollDone();
    assertEquals(1, done.size());
    db.markApplied(java.util.List.of(done.get(0).id()));
    assertTrue(db.pollDone().isEmpty());
  }

  @Test void countsGroupJobsByEngineAndStatus() {
    enqueue("u1", 1, true, "a");
    enqueue("u2", 1, true, "a");
    enqueue("u3", 1, true, "b");
    db.claim("w", "b");
    var counts = db.counts();
    assertEquals(2L, counts.get("a|queued"));
    assertEquals(1L, counts.get("b|processing"));
  }

  @Test void knowsPathsOfEnqueuedAudioOnly() {
    enqueue("u1", 1, true, "e");
    assertTrue(db.knowsPath("/x/u1"));
    assertFalse(db.knowsPath("/x/other"));
  }

  @Test void guildRegistrationRoundTripsAndReRegisteringReplacesTheChannel() {
    assertNull(db.channelOf(1));
    db.register(1, 10);
    db.register(1, 11);
    assertEquals(11L, db.channelOf(1));
    assertNull(db.channelOf(2));
    db.unregister(1);
    assertNull(db.channelOf(1));
  }

  @Test void oldGuildRegistrationsMigrateOnceAndDoNotOverwriteNewRoutes() throws Exception {
    var tmp = Files.createTempDirectory("cwdb-migration");
    Path file = tmp.resolve("q.db");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
         var s = c.createStatement()) {
      s.execute("CREATE TABLE guilds(guild_id INTEGER PRIMARY KEY, channel_id INTEGER NOT NULL)");
      s.execute("INSERT INTO guilds(guild_id, channel_id) VALUES(1, 10)");
    }
    var cfg = Config.of(Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", file.toString())::get);
    var migrated = new Db(cfg);
    assertEquals(10L, migrated.channelOf(1));

    migrated.register(1, 11);
    var restarted = new Db(cfg);
    assertEquals(11L, restarted.channelOf(1));
  }

  @Test void engineRoutesOverrideDefaultAndFallBackAfterRemoval() {
    db.register(1, 10);
    db.register(1, "a", 11);
    db.register(2, "b", 22);

    assertEquals(11L, db.routeOf(1, "a"));
    assertEquals(10L, db.routeOf(1, "b"));
    assertEquals(Map.of("a", 11L, "b", 10L), db.routesOf(1, java.util.List.of("a", "b")));
    assertEquals(Map.of("b", 22L), db.routesOf(2, java.util.List.of("a", "b")));

    db.unregister(1, "a");
    assertEquals(10L, db.routeOf(1, "a"));
    assertTrue(db.hasRoutes(1));
    db.unregister(1);
    assertFalse(db.hasRoutes(1));
  }

  @Test void engineOnlyRoutesKeepGuildEnabledWithoutChangingOtherRoutes() {
    db.register(1, "a", 10);
    db.register(1, "b", 10);

    assertTrue(db.hasRoutes(1));
    assertNull(db.routeOf(1, "c"));
    assertEquals(Map.of("a", 10L, "b", 10L), db.routesOf(1, java.util.List.of("a", "b", "c")));
    db.unregister(1, "a");
    assertTrue(db.hasRoutes(1));
    assertEquals(10L, db.routeOf(1, "b"));
  }

  @Test void staleJobsAreFailedOnceRetriesAreUsedUp() {
    enqueue("u1", 1, true, "e");
    db.claim("dead-worker", "e");
    assertEquals(1, db.requeueStale(-1000, 0));  // retry_count 0 >= retryMax 0 なので、キューへ戻さず失敗にする
    assertTrue(db.claim("w", "e").isEmpty());
    assertEquals("failed", db.pollDone().get(0).status());
  }
}

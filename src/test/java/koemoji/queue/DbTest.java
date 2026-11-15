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
  Config cfg;
  Path queueDb;

  @BeforeEach void setUp() throws Exception {
    var tmp = Files.createTempDirectory("cwdb");
    queueDb = tmp.resolve("q.db");
    var env = Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", queueDb.toString());
    cfg = Config.of(env::get);
    db = new Db(cfg);
  }

  private java.sql.Connection holdWriteLock() throws Exception {
    var h = DriverManager.getConnection("jdbc:sqlite:" + queueDb);
    try (var s = h.createStatement()) { s.execute("BEGIN IMMEDIATE"); }
    return h;
  }

  private static void release(java.sql.Connection h) throws Exception {
    try (var s = h.createStatement()) { s.execute("ROLLBACK"); }
  }

  @Test void journalModeIsWalAfterInit() throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + queueDb); var s = c.createStatement(); var r = s.executeQuery("PRAGMA journal_mode")) {
      assertTrue(r.next());
      assertEquals("wal", r.getString(1));
    }
  }

  @Test void enqueueSucceedsWhenLockIsReleasedBeforeTheRetry() throws Exception {
    var busy = new Db(cfg, 300);
    try (var h = holdWriteLock()) {
      var f = java.util.concurrent.CompletableFuture.runAsync(() -> busy.enqueue("u1", 1, true, "/x/u1", 100, "e"));
      Thread.sleep(400);
      release(h);
      f.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(1L, busy.counts().get("e|queued"));
  }

  @Test void completeSucceedsWhenLockIsReleasedBeforeTheRetry() throws Exception {
    var busy = new Db(cfg, 300);
    busy.enqueue("u1", 1, true, "/x/u1", 100, "e");
    busy.claim("w", "e");
    try (var h = holdWriteLock()) {
      var f = java.util.concurrent.CompletableFuture.runAsync(() -> busy.complete(1, "w", "こんにちは"));
      Thread.sleep(400);
      release(h);
      f.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(1L, busy.counts().get("e|completed"));
    assertEquals("こんにちは", busy.pollDone().get(0).text());
  }

  @Test void enqueueFailsWithBusyAfterOneRetryWhileLockIsHeld() throws Exception {
    var busy = new Db(cfg, 300);
    try (var h = holdWriteLock()) {
      long start = System.nanoTime();
      var e = assertThrows(IllegalStateException.class, () -> busy.enqueue("u1", 1, true, "/x/u1", 100, "e"));
      long tookMs = (System.nanoTime() - start) / 1_000_000;
      var cause = assertInstanceOf(org.sqlite.SQLiteException.class, e.getCause());
      assertEquals(5, cause.getResultCode().code & 0xff);
      assertTrue(tookMs >= 600, "took " + tookMs + " ms");
      release(h);
    }
    assertTrue(busy.counts().isEmpty());
  }

  @Test void nonBusyFailureIsNotRetried() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var e = assertThrows(IllegalStateException.class, () -> db.txRetryingBusy("x", c -> {
      calls.incrementAndGet();
      throw new java.sql.SQLException("boom");
    }));
    assertEquals("boom", e.getCause().getMessage());
    assertEquals(1, calls.get());
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

  @Test void oldestQueuedAgeIsPerEngineAndIgnoresNonQueuedJobs() throws Exception {
    assertEquals(Map.of(), db.oldestQueuedAgeMs(System.currentTimeMillis()));
    long before = System.currentTimeMillis();
    enqueue("u1", 1, true, "a");
    long after = System.currentTimeMillis();
    Thread.sleep(20);
    enqueue("u2", 1, true, "a");
    enqueue("u3", 1, true, "b");
    long now = System.currentTimeMillis() + 5_000;
    var ages = db.oldestQueuedAgeMs(now);
    assertEquals(java.util.Set.of("a", "b"), ages.keySet());
    assertTrue(ages.get("a") >= now - after && ages.get("a") <= now - before, "a=" + ages.get("a"));
    assertTrue(ages.get("b") < ages.get("a"));

    db.claim("w", "a");
    db.claim("w", "a");
    assertEquals(java.util.Set.of("b"), db.oldestQueuedAgeMs(now).keySet());
    assertEquals(0L, db.oldestQueuedAgeMs(0).get("b"));
  }

  @Test void failedSinceCountsPerEngineFromTheGivenInstantInclusive() throws Exception {
    assertEquals(Map.of(), db.failedSince(0));
    enqueue("u1", 1, false, "e");
    db.fail(db.claim("w", "e").get(), "w", "boom", 0, 0);
    enqueue("u2", 1, false, "other");
    var ok = db.claim("w", "other").get();
    db.complete(ok.id(), "w", "text");
    long completedAt;
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + queueDb); var s = c.createStatement();
         var r = s.executeQuery("SELECT completed_at FROM jobs WHERE status='failed'")) {
      assertTrue(r.next());
      completedAt = r.getLong(1);
    }
    assertEquals(Map.of("e", 1L), db.failedSince(completedAt));
    assertEquals(Map.of(), db.failedSince(completedAt + 1));
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

  private static final int PRODUCERS = 4, PER_PRODUCER = 25, WORKERS = 4, TOTAL = PRODUCERS * PER_PRODUCER;

  /** reaper が回収した件数を返す。 */
  private int runConcurrently(boolean withReaper) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    var completed = new java.util.concurrent.atomic.AtomicInteger();
    var reaped = new java.util.concurrent.atomic.AtomicInteger();
    var claimed = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
    var duplicates = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(PRODUCERS + WORKERS + 1);
    try {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
      for (int p = 0; p < PRODUCERS; p++) {
        int producer = p;
        tasks.add(pool.submit(() -> {
          for (int i = 0; i < PER_PRODUCER; i++) enqueue("u" + producer + "-" + i, 1, true, "e");
          return null;
        }));
      }
      for (int w = 0; w < WORKERS; w++) {
        String worker = "w" + w;
        tasks.add(pool.submit(() -> {
          while (completed.get() < TOTAL && System.nanoTime() < deadline) {
            var job = db.claim(worker, "e");
            if (job.isEmpty()) { Thread.sleep(5); continue; }
            if (!claimed.add(job.get().id())) duplicates.add(job.get().id());
            db.complete(job.get().id(), worker, "ok");
            completed.incrementAndGet();
          }
          return null;
        }));
      }
      if (withReaper) {
        tasks.add(pool.submit(() -> {
          while (completed.get() < TOTAL && System.nanoTime() < deadline) {
            reaped.addAndGet(db.requeueStale(60_000, 5));
            Thread.sleep(5);
          }
          return null;
        }));
      }
      for (var t : tasks) t.get(35, java.util.concurrent.TimeUnit.SECONDS);  // スレッド内の例外をここで再スローする
    } finally {
      pool.shutdownNow();
    }
    assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(TOTAL, completed.get());
    assertEquals(java.util.List.of(), java.util.List.copyOf(duplicates));
    assertEquals(Map.of("e|completed", (long) TOTAL), db.counts());
    return reaped.get();
  }

  @Test void concurrentEnqueueClaimAndCompleteLoseNoJobAndClaimEachOnce() throws Exception {
    runConcurrently(false);
  }

  @Test void reaperRunningConcurrentlyDoesNotRequeueFreshProcessingJobs() throws Exception {
    assertEquals(0, runConcurrently(true));
  }

  @Test void staleJobsAreFailedOnceRetriesAreUsedUp() {
    enqueue("u1", 1, true, "e");
    db.claim("dead-worker", "e");
    assertEquals(1, db.requeueStale(-1000, 0));  // retry_count 0 >= retryMax 0 なので、キューへ戻さず失敗にする
    assertTrue(db.claim("w", "e").isEmpty());
    assertEquals("failed", db.pollDone().get(0).status());
  }
}

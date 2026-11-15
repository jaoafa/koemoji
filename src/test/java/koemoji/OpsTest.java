package koemoji;

import koemoji.queue.Db;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsTest {
  private static Config config(java.util.Map<String, String> extra) throws Exception {
    var tmp = Files.createTempDirectory("cwops");
    var env = new HashMap<>(extra);
    env.put("AUDIO_DIR", tmp.resolve("audio").toString());
    env.put("QUEUE_DB", tmp.resolve("q.db").toString());
    env.put("HEALTH_PORT", "0");
    return Config.of(env::get);
  }

  private static HttpResponse<String> get(Ops ops, String path) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + ops.port() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> update(Ops ops, String path, String method, String secret) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + ops.port() + path));
    if (secret != null) request.header("X-Koemoji-Update-Secret", secret);
    return HttpClient.newHttpClient().send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test void healthFailsWhileDiscordIsDisconnectedAndPassesOnceConnected() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture"));
    var up = new java.util.concurrent.atomic.AtomicBoolean();
    var db = new Db(c);
    var ops = new Ops(c, db, up::get, new UpdateDrain(db, null, null));
    try {
      assertEquals(503, get(ops, "/health").statusCode());
      up.set(true);
      assertEquals(200, get(ops, "/health").statusCode());
    } finally { ops.close(); }
  }

  @Test void metricsExposeJobCountsPerEngineAndStatus() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture"));
    var db = new Db(c);
    db.enqueue("u1", 1, true, "/x", 10, "sensevoice");
    var ops = new Ops(c, db, () -> true, new UpdateDrain(db, null, null));
    try {
      assertTrue(get(ops, "/metrics").body().contains("koemoji_jobs{engine=\"sensevoice\",status=\"queued\"} 1"));
    } finally { ops.close(); }
  }

  private static double metricValue(String body, String name) {
    for (String line : body.split("\n"))
      if (line.startsWith(name + " ")) return Double.parseDouble(line.substring(name.length() + 1));
    throw new AssertionError(name + " not found in:\n" + body);
  }

  @Test void metricsExposeOldestQueuedAgePerEngineAndZeroForIdleConfiguredEngines() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture"));
    var db = new Db(c);
    long before = System.currentTimeMillis();
    db.enqueue("u1", 1, true, "/x", 10, "sensevoice");
    long after = System.currentTimeMillis();
    long now = after + 12_000;
    String body = Ops.metrics(db, List.of("sensevoice", "reazon-ja"), now);
    double age = metricValue(body, "koemoji_oldest_queued_age_seconds{engine=\"sensevoice\"}");
    assertTrue(age >= 12.0 && age <= (now - before) / 1000.0, "age=" + age);
    assertEquals(0.0, metricValue(body, "koemoji_oldest_queued_age_seconds{engine=\"reazon-ja\"}"));
    assertTrue(body.contains("# TYPE koemoji_oldest_queued_age_seconds gauge\n"));
  }

  @Test void metricsCountOnlyFailuresInsideTheFiveMinuteWindow() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture"));
    var db = new Db(c);
    db.enqueue("u1", 1, false, "/x", 10, "sensevoice");
    db.fail(db.claim("w", "sensevoice").orElseThrow(), "w", "boom", 0, 0);
    long completedAt;
    try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + c.queueDb()); var s = conn.createStatement();
         var r = s.executeQuery("SELECT completed_at FROM jobs WHERE status='failed'")) {
      assertTrue(r.next());
      completedAt = r.getLong(1);
    }
    var engines = List.of("sensevoice", "reazon-ja");
    String inside = Ops.metrics(db, engines, completedAt + 300_000);
    assertEquals(1.0, metricValue(inside, "koemoji_jobs_failed_5m{engine=\"sensevoice\"}"));
    assertEquals(0.0, metricValue(inside, "koemoji_jobs_failed_5m{engine=\"reazon-ja\"}"));
    String outside = Ops.metrics(db, engines, completedAt + 300_001);
    assertEquals(0.0, metricValue(outside, "koemoji_jobs_failed_5m{engine=\"sensevoice\"}"));
  }

  @Test void healthIgnoresQueueAgeInCaptureOnlyProcesses() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture", "HEALTH_MAX_QUEUE_AGE_SEC", "1"));
    var db = new Db(c);
    db.enqueue("u1", 1, true, "/x", 10, "sensevoice");
    var ops = new Ops(c, db, () -> true, new UpdateDrain(db, null, null), () -> System.currentTimeMillis() + 3_600_000);
    try {
      assertEquals(200, get(ops, "/health").statusCode());
    } finally { ops.close(); }
  }

  @Test void stalledEnginesReturnsOnlyEnginesStrictlyOverTheThresholdInOrder() {
    var ages = java.util.Map.of("b", 1_801_000L, "a", 1_800_001L, "c", 1_800_000L);
    assertEquals(List.of("a", "b"), Ops.stalledEngines(ages, 1800));
    assertEquals(List.of(), Ops.stalledEngines(ages, 0));
  }

  @Test void queueProblemReportsTheStalledWorkerEngineAtTheExactThreshold() {
    var engines = List.of("sensevoice");
    assertEquals(java.util.Optional.empty(), Ops.queueProblem(() -> java.util.Map.of("sensevoice", 1_800_000L), engines, 1800));
    assertEquals(java.util.Optional.of("queue stalled: sensevoice oldest queued 1801s > 1800s"),
        Ops.queueProblem(() -> java.util.Map.of("sensevoice", 1_801_000L), engines, 1800));
  }

  @Test void queueProblemIsDisabledByZeroWithoutReadingTheQueue() {
    assertEquals(java.util.Optional.empty(), Ops.queueProblem(() -> { throw new AssertionError("queue must not be read"); }, List.of("e"), 0));
  }

  @Test void queueProblemIgnoresEnginesThisWorkerDoesNotServe() {
    var ages = java.util.Map.of("other", 9_999_000L, "mine", 5_000_000L);
    assertEquals(java.util.Optional.of("queue stalled: mine oldest queued 5000s > 1800s"), Ops.queueProblem(() -> ages, List.of("mine"), 1800));
    assertEquals(java.util.Optional.empty(), Ops.queueProblem(() -> java.util.Map.of("other", 9_999_000L), List.of("mine"), 1800));
  }

  @Test void queueProblemTreatsAFailedQueueReadAsNoProblem() {
    assertEquals(java.util.Optional.empty(), Ops.queueProblem(() -> { throw new IllegalStateException("busy"); }, List.of("e"), 1800));
  }

  @Test void healthCheckPassesWhenTheHealthServerIsDisabled() throws Exception {
    assertEquals(0, HealthCheck.check(0));
  }

  @Test void healthCheckFollowsTheHealthEndpoint() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture"));
    var up = new java.util.concurrent.atomic.AtomicBoolean();
    var db = new Db(c);
    var ops = new Ops(c, db, up::get, new UpdateDrain(db, null, null));
    try {
      assertEquals(1, HealthCheck.check(ops.port()));
      up.set(true);
      assertEquals(0, HealthCheck.check(ops.port()));
    } finally { ops.close(); }
  }

  @Test void updateControlRequiresTheConfiguredSecret() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture", "UPDATE_CONTROL_SECRET", "test-secret"));
    var db = new Db(c);
    var ops = new Ops(c, db, () -> true, new UpdateDrain(db, null, null));
    try {
      assertEquals(401, update(ops, "/update/status", "GET", null).statusCode());
      assertEquals(401, update(ops, "/update/drain", "POST", "wrong-secret").statusCode());
      assertEquals(200, update(ops, "/update/status", "GET", "test-secret").statusCode());
    } finally { ops.close(); }
  }

  @Test void drainWaitsForJobsAndAppliedResultsBeforeReportingReady() throws Exception {
    var c = config(java.util.Map.of("MODE", "capture", "UPDATE_CONTROL_SECRET", "test-secret"));
    var db = new Db(c);
    db.enqueue("u1", 1, true, "/x", 10, "sensevoice");
    var drain = new UpdateDrain(db, null, null);
    var ops = new Ops(c, db, () -> true, drain);
    try {
      assertEquals(202, update(ops, "/update/drain", "POST", "test-secret").statusCode());
      assertTrue(drain.status().phase() != UpdateDrain.Phase.READY);
      var job = db.claim("test-worker", "sensevoice").orElseThrow();
      db.complete(job.id(), "test-worker", "recognized");
      assertTrue(waitFor(() -> db.unappliedResultCount() == 1));
      assertEquals(UpdateDrain.Phase.DRAINING, drain.status().phase());
      db.markApplied(List.of(job.id()));
      assertTrue(waitFor(() -> drain.status().phase() == UpdateDrain.Phase.READY));
    } finally { ops.close(); }
  }

  private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }
}

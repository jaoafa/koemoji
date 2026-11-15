package koemoji;

import koemoji.asr.Worker;
import koemoji.queue.Db;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** ヘルスチェックと Prometheus 形式のメトリクスを、JDK 組み込みの HTTP サーバーで提供する。 */
final class Ops {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Ops.class);
  private static final long FAILED_WINDOW_MS = 300_000;
  private static final long STALL_CHECK_SEC = 60;
  private final HttpServer server;
  private final ScheduledExecutorService stallWatcher;

  Ops(Config c, Db db, BooleanSupplier discordConnected, UpdateDrain drain) throws IOException {
    this(c, db, discordConnected, drain, System::currentTimeMillis);
  }

  Ops(Config c, Db db, BooleanSupplier discordConnected, UpdateDrain drain, LongSupplier nowMs) throws IOException {
    server = HttpServer.create(new InetSocketAddress(c.healthPort()), 0);
    server.createContext("/health", ex -> {
      String problem = null;
      if (c.capture() && !discordConnected.getAsBoolean()) problem = "discord not connected";
      else if (c.worker() && Worker.alive() < c.workerEngines().size())
        problem = "workers alive " + Worker.alive() + "/" + c.workerEngines().size();
      if (problem == null && c.worker())
        problem = queueProblem(() -> db.oldestQueuedAgeMs(nowMs.getAsLong()), c.workerEngines(), c.healthMaxQueueAgeSec()).orElse(null);
      reply(ex, problem == null ? 200 : 503, problem == null ? "ok\n" : problem + "\n");
    });
    server.createContext("/metrics", ex -> {
      String body;
      try { body = metrics(db, c.engines(), nowMs.getAsLong()); }
      catch (RuntimeException e) {  // JDK のサーバーは、これを TRACE でログに出して接続を切るだけになる
        log.warn("metrics failed", e);
        reply(ex, 503, "metrics unavailable\n");
        return;
      }
      reply(ex, 200, body);
    });
    server.createContext("/update/status", ex -> {
      if (!authorized(ex, c.updateSecret())) { reply(ex, 401, "unauthorized\n"); return; }
      var status = drain.status();
      String body = "{\"phase\":\"" + status.phase() + "\",\"pendingJobs\":" + status.pendingJobs()
          + ",\"unappliedResults\":" + status.unappliedResults() + ",\"transcriptInflight\":" + status.transcriptInflight()
          + ",\"activeWorkers\":" + status.activeWorkers() + ",\"activePipelines\":" + status.activePipelines() + "}\n";
      reply(ex, 200, body, "application/json; charset=utf-8");
    });
    server.createContext("/update/drain", ex -> {
      if (!authorized(ex, c.updateSecret())) { reply(ex, 401, "unauthorized\n"); return; }
      if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, "method not allowed\n"); return; }
      drain.begin();
      reply(ex, 202, "accepted\n");
    });
    server.createContext("/update/cancel", ex -> {
      if (!authorized(ex, c.updateSecret())) { reply(ex, 401, "unauthorized\n"); return; }
      if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, "method not allowed\n"); return; }
      drain.cancel();
      reply(ex, 200, "cancelled\n");
    });
    if (c.updateSecret().isBlank()) log.warn("Automatic updates are disabled because UPDATE_CONTROL_SECRET is empty");
    server.start();
    if (c.queueStallWarnSec() > 0) {
      stallWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "queue-stall-watcher");
        t.setDaemon(true);
        return t;
      });
      stallWatcher.scheduleWithFixedDelay(() -> warnStalled(db, nowMs, c.queueStallWarnSec()), STALL_CHECK_SEC, STALL_CHECK_SEC, TimeUnit.SECONDS);
    } else stallWatcher = null;
  }

  private static void warnStalled(Db db, LongSupplier nowMs, int thresholdSec) {
    try {
      var ages = db.oldestQueuedAgeMs(nowMs.getAsLong());
      for (String engine : stalledEngines(ages, thresholdSec))
        log.warn("queue stalled: engine={} oldest queued {}s > {}s", engine, ages.get(engine) / 1000, thresholdSec);
    } catch (RuntimeException e) {  // 例外で止まると以後の周期が実行されない
      log.warn("queue stall check failed", e);
    }
  }

  /** しきい値(秒)を超えて queued のままのジョブがあるエンジンを辞書順で返す。しきい値が 0 以下なら判定しない。 */
  static List<String> stalledEngines(Map<String, Long> agesMs, long thresholdSec) {
    if (thresholdSec <= 0) return List.of();
    return agesMs.entrySet().stream().filter(e -> e.getValue() > thresholdSec * 1000L).map(Map.Entry::getKey).sorted().toList();
  }

  /**
   * この worker が担当するエンジンの滞留を /health の問題として返す。しきい値が 0 以下なら DB を読まない。
   * DB の読み取りに失敗したときは、一時的な SQLITE_BUSY で unhealthy にしないよう、問題なしとして扱う。
   */
  static Optional<String> queueProblem(Supplier<Map<String, Long>> agesMs, Collection<String> workerEngines, long thresholdSec) {
    if (thresholdSec <= 0) return Optional.empty();
    Map<String, Long> ages;
    try {
      ages = agesMs.get();
    } catch (RuntimeException e) {
      log.warn("health queue check failed", e);
      return Optional.empty();
    }
    var mine = new HashMap<>(ages);
    mine.keySet().retainAll(workerEngines);
    return stalledEngines(mine, thresholdSec).stream().findFirst()
        .map(engine -> "queue stalled: " + engine + " oldest queued " + mine.get(engine) / 1000 + "s > " + thresholdSec + "s");
  }

  int port() { return server.getAddress().getPort(); }

  void close() {
    server.stop(0);
    if (stallWatcher != null) stallWatcher.shutdownNow();
  }

  static String metrics(Db db, Collection<String> engines, long nowMs) {
    var sb = new StringBuilder();
    sb.append("# TYPE koemoji_jobs gauge\n");
    db.counts().forEach((k, n) -> {
      String[] p = k.split("\\|");
      sb.append("koemoji_jobs{engine=\"").append(p[0]).append("\",status=\"").append(p[1]).append("\"} ").append(n).append('\n');
    });
    sb.append("# TYPE koemoji_workers_alive gauge\nkoemoji_workers_alive ").append(Worker.alive()).append('\n');
    sb.append("# TYPE koemoji_asr_jobs_total counter\n# TYPE koemoji_asr_seconds_total counter\n");
    Worker.stats().forEach((k, v) -> {
      String[] p = k.split("\\|");
      String labels = "{engine=\"" + p[0] + "\",kind=\"" + p[1] + "\"}";
      sb.append("koemoji_asr_jobs_total").append(labels).append(' ').append(v[0]).append('\n');
      sb.append("koemoji_asr_seconds_total").append(labels).append(' ').append(v[1] / 1e9).append('\n');
    });
    var ages = db.oldestQueuedAgeMs(nowMs);
    var failed = db.failedSince(nowMs - FAILED_WINDOW_MS);
    var all = new TreeSet<>(engines);
    all.addAll(ages.keySet());
    all.addAll(failed.keySet());
    sb.append("# TYPE koemoji_oldest_queued_age_seconds gauge\n");
    for (String e : all)
      sb.append("koemoji_oldest_queued_age_seconds{engine=\"").append(e).append("\"} ").append(ages.getOrDefault(e, 0L) / 1000.0).append('\n');
    sb.append("# TYPE koemoji_jobs_failed_5m gauge\n");
    for (String e : all)
      sb.append("koemoji_jobs_failed_5m{engine=\"").append(e).append("\"} ").append(failed.getOrDefault(e, 0L)).append('\n');
    return sb.toString();
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
    reply(ex, code, body, "text/plain; charset=utf-8");
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body, String contentType) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.sendResponseHeaders(code, b.length);
    try (var o = ex.getResponseBody()) { o.write(b); }
  }

  private static boolean authorized(com.sun.net.httpserver.HttpExchange ex, String secret) {
    if (secret == null || secret.isBlank()) return false;
    String supplied = ex.getRequestHeaders().getFirst("X-Koemoji-Update-Secret");
    return supplied != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }
}

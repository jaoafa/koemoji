package koemoji;

import koemoji.asr.Worker;
import koemoji.queue.Db;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** ヘルスチェックと Prometheus 形式のメトリクスを、JDK 組み込みの HTTP サーバーで提供する。 */
final class Ops {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Ops.class);
  private final HttpServer server;

  Ops(Config c, Db db, BooleanSupplier discordConnected, UpdateDrain drain) throws IOException {
    server = HttpServer.create(new InetSocketAddress(c.healthPort()), 0);
    server.createContext("/health", ex -> {
      String problem = null;
      if (c.capture() && !discordConnected.getAsBoolean()) problem = "discord not connected";
      else if (c.worker() && Worker.alive() < c.workerEngines().size())
        problem = "workers alive " + Worker.alive() + "/" + c.workerEngines().size();
      reply(ex, problem == null ? 200 : 503, problem == null ? "ok\n" : problem + "\n");
    });
    server.createContext("/metrics", ex -> {
      String body;
      try { body = metrics(db); }
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
  }

  int port() { return server.getAddress().getPort(); }

  void close() { server.stop(0); }

  static String metrics(Db db) {
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

package koemoji.asr;

import koemoji.Config;
import koemoji.queue.Db;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded pool of ASR workers polling the SQLite queue, plus TTL/crash-recovery reaper. */
public final class Worker {
  private static final Logger log = LoggerFactory.getLogger(Worker.class);
  private static final long STALE_MS = 5 * 60_000;

  private static final AtomicInteger alive = new AtomicInteger();
  /** Per "engine|final|partial": [jobs, nanoseconds spent recognizing]. */
  private static final Map<String, long[]> stats = new ConcurrentHashMap<>();

  private final AtomicBoolean acceptingClaims = new AtomicBoolean(true);
  private final AtomicInteger activeJobs = new AtomicInteger();

  private Worker() {}

  public static int alive() { return alive.get(); }
  public static Map<String, long[]> stats() { return stats; }

  public static Worker start(Config c, Db db) {
    Worker worker = new Worker();
    String host = System.getenv().getOrDefault("HOSTNAME", "local") + "-" + ProcessHandle.current().pid();
    // Engines are built here, before any thread starts, so a missing/invalid model fails the process at startup
    // (and the restart policy applies) instead of silently killing one worker thread.
    for (int i = 0; i < c.workerEngines().size(); i++) {
      String id = host + "-" + i, name = c.workerEngines().get(i);
      AsrEngine engine = AsrEngine.create(name, c);
      Thread.ofPlatform().daemon().name("asr-" + name + "-" + i).start(() -> worker.loop(c, db, id, name, engine));
    }
    var reaper = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("reaper").unstarted(r));
    reaper.scheduleWithFixedDelay(() -> {
      try { reap(c, db); } catch (Throwable t) { log.warn("reaper failed", t); }  // an Error would silently cancel the schedule
    }, 10, 30, TimeUnit.SECONDS);
    return worker;
  }

  public void stopClaims() { acceptingClaims.set(false); }
  public void resumeClaims() { acceptingClaims.set(true); }
  public boolean isIdle() { return activeJobs.get() == 0; }

  private void loop(Config c, Db db, String id, String engineName, AsrEngine engine) {
    boolean up = false;
    long backoff = 1000;
    try (engine) {
      log.info("worker {} ready ({}, pad={} ms, normalize={}, decoding={}, blankPenalty={})", id, engine.capabilities(),
          c.padMsFor(engineName), c.normalizes(engineName), c.decodingMethodFor(engineName), c.blankPenaltyFor(engineName));
      alive.incrementAndGet();
      up = true;
      while (!Thread.currentThread().isInterrupted()) {
        try {
          if (!acceptingClaims.get()) { Thread.sleep(100); continue; }
          var job = db.claim(id, engineName);
          if (job.isEmpty()) { Thread.sleep(100); continue; }
          activeJobs.incrementAndGet();
          try { run(c, db, id, engine, engineName, job.get()); }
          finally { activeJobs.decrementAndGet(); }
          backoff = 1000;
        } catch (InterruptedException e) {
          throw e;
        } catch (Exception e) {
          // transient DB trouble must not kill the thread; a job left in 'processing' is requeued by the reaper.
          // Backing off keeps a persistent failure from flooding the log.
          log.warn("worker {} iteration failed; retrying in {} ms", id, backoff, e);
          Thread.sleep(backoff);
          backoff = Math.min(backoff * 2, 30_000);
        }
      }
    } catch (InterruptedException ignored) {
    } catch (Throwable t) {
      // an Error (e.g. OOM) must not leave a bot that looks online but transcribes nothing: exit so Docker restarts it
      log.error("worker {} died; exiting", id, t);
      System.exit(1);
    } finally {
      if (up) alive.decrementAndGet();
    }
  }

  private static void run(Config c, Db db, String id, AsrEngine engine, String engineName, Db.Job j) {
    try {
      float[] raw = read(j.audioPath(), j.snapshotBytes());
      long t = System.nanoTime();
      AsrEngine.Result result = recognize(engine, raw, c, engineName, j.isFinal());
      long took = System.nanoTime() - t;
      db.complete(j.id(), id, result.text(), result.logProbabilitySum(), result.scoredTokenCount());
      stats.merge(engineName + "|" + (j.isFinal() ? "final" : "partial"), new long[] {1, took},
          (a, b) -> new long[] {a[0] + b[0], a[1] + b[1]});
    } catch (Exception e) {
      log.warn("job {} failed (retry {})", j.id(), j.retryCount(), e);
      db.fail(j, id, String.valueOf(e), c.retryMax(), c.retryBackoffMs());
    }
  }

  /**
   * {@code raw} を認識する。ほぼ無音の音声と、エンジンにしきい値があるときの平均対数確率がそれ未満の出力は、
   * 空の結果を返す (表示されない)。ほぼ無音かどうかは正規化の前の音声で判定し、その場合はエンジンを実行しない。
   */
  public static AsrEngine.Result recognize(AsrEngine engine, float[] raw, Config c, String engineName, boolean isFinal) {
    var discarded = new AsrEngine.Result("", 0, 0);
    if (peak(raw) < c.minPeak()) return discarded;
    float[] pcm = preprocess(raw, c, engineName);
    AsrEngine.Result result = isFinal ? engine.recognizeFinal(pcm) : engine.recognizePartial(pcm);
    boolean lowConfidence = result.scoredTokenCount() > 0
        && result.logProbabilitySum() / result.scoredTokenCount() < c.minAvgLogProbFor(engineName);
    return lowConfidence ? discarded : result;
  }

  public static float peak(float[] x) {
    float max = 0;
    for (float v : x) max = Math.max(max, Math.abs(v));
    return max;
  }

  public static float[] preprocess(float[] x, Config c, String engine) {
    float[] y = c.normalizes(engine) ? normalize(x, c.normalizeTargetDbfs(), c.normalizeMaxGainDb(), c.normalizePeak(), c.normalizeSilenceDbfs()) : x;
    return pad(y, c.padMsFor(engine));
  }

  /** RMS が silenceDbfs 未満の音声は、増幅するとモデルがノイズから文字を出力するため、そのまま返す。 */
  public static float[] normalize(float[] x, float targetDbfs, float maxGainDb, float peak, float silenceDbfs) {
    double sumSquares = 0, maxAbs = 0;
    for (float v : x) {
      sumSquares += (double) v * v;
      maxAbs = Math.max(maxAbs, Math.abs(v));
    }
    if (x.length == 0 || sumSquares == 0) return x;
    double rmsDb = 10 * Math.log10(sumSquares / x.length);
    if (rmsDb < silenceDbfs) return x;
    double gain = Math.min(Math.pow(10, (targetDbfs - rmsDb) / 20), Math.pow(10, maxGainDb / 20.0));
    gain = Math.min(gain, peak / maxAbs);
    float[] out = new float[x.length];
    for (int i = 0; i < out.length; i++) out[i] = (float) (x[i] * gain);
    return out;
  }

  /** Zero-pads both ends; some models drop the first/last sound when audio starts or stops abruptly. */
  public static float[] pad(float[] x, int ms) {
    int n = ms * 16;
    if (n <= 0) return x;
    float[] out = new float[x.length + 2 * n];
    System.arraycopy(x, 0, out, n, x.length);
    return out;
  }

  /** Reads only the first snapshotBytes so a still-growing recording file is an immutable snapshot. */
  public static float[] read(String path, long bytes) throws IOException {
    byte[] b = new byte[(int) bytes];
    try (var f = new RandomAccessFile(path, "r")) { f.readFully(b); }
    var s = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    float[] out = new float[s.remaining()];
    for (int i = 0; i < out.length; i++) out[i] = s.get(i) / 32768f;
    return out;
  }

  private static void reap(Config c, Db db) throws IOException {
    int n = db.requeueStale(STALE_MS, c.retryMax());
    if (n > 0) log.warn("requeued {} stale jobs", n);
    for (String[] e : db.expired(c.audioTtlMin() * 60_000L, c.failedAudioTtlMin() * 60_000L)) {
      try {
        Files.deleteIfExists(Path.of(e[1]));
        db.deleteUtterance(e[0]);
      } catch (IOException ex) { log.warn("could not delete {}", e[1], ex); }  // one bad file must not stop the rest
    }
    // orphans: files never referenced by any job (e.g. capture crashed before the first job was enqueued)
    long cutoff = System.currentTimeMillis() - 3_600_000;
    try (var ls = Files.list(c.audioDir())) {
      for (Path p : (Iterable<Path>) ls::iterator) {
        try {
          if (Files.getLastModifiedTime(p).toMillis() < cutoff && !db.knowsPath(p.toString())) Files.deleteIfExists(p);
        } catch (IOException ex) { log.warn("could not clean {}", p, ex); }
      }
    }
  }
}

package koemoji;

import koemoji.asr.Models;
import koemoji.asr.SherpaEngine;
import koemoji.asr.Worker;
import koemoji.capture.Bot;
import koemoji.queue.Db;

public final class Main {
  public static void main(String[] args) throws Exception {
    Config c = Config.fromEnv();
    validate(c);
    Db db = new Db(c);
    Bot bot = null;
    if (c.capture()) {
      Models.ensureCapture(c);
      if (c.token() == null || c.token().isBlank()) throw new IllegalStateException("DISCORD_TOKEN is required");
      bot = new Bot(c, db);
      bot.start();
    }
    Models.ensureWorkers(c);
    Worker worker = c.worker() ? Worker.start(c, db) : null;
    Bot b = bot;
    if (c.healthPort() > 0) new Ops(c, db, () -> b != null && b.connected(), new UpdateDrain(db, bot, worker));
    Thread.currentThread().join();
  }

  /** Fails fast on config that would silently do nothing or strand jobs: bad MODE, empty or unknown engines, engines no local worker serves. */
  static void validate(Config c) {
    if (!java.util.Set.of("all", "capture", "worker").contains(c.mode()))
      throw new IllegalStateException("MODE must be all, capture or worker: " + c.mode());
    if (c.engines().isEmpty()) throw new IllegalStateException("ASR_ENGINES must name at least one engine");
    if (c.worker() && c.workerEngines().isEmpty()) throw new IllegalStateException("WORKER_ENGINES must name at least one worker (count >= 1)");
    if (c.maxUtteranceMs() <= 0) throw new IllegalStateException("MAX_UTTERANCE_MS must be positive: " + c.maxUtteranceMs());
    if (c.vadEndSilenceMs() <= 0) throw new IllegalStateException("VAD_END_SILENCE_MS must be positive: " + c.vadEndSilenceMs());
    for (String e : java.util.stream.Stream.concat(c.engines().stream(), c.workerEngines().stream()).toList())
      if (!SherpaEngine.DIRS.containsKey(e)) throw new IllegalStateException("Unknown engine: " + e + " (known: " + SherpaEngine.DIRS.keySet() + ")");
    if (c.capture() && c.worker())
      for (String e : c.engines())
        if (!c.workerEngines().contains(e)) throw new IllegalStateException("No worker for engine " + e + " (add it to WORKER_ENGINES)");
    if (c.padMs() < 0) throw new IllegalStateException("ASR_PAD_MS must not be negative: " + c.padMs());
    for (var e : c.padMsByEngine().entrySet())
      if (e.getValue() < 0) throw new IllegalStateException("ASR_PAD_MS_BY_ENGINE must not be negative: " + e.getKey() + ":" + e.getValue());
    requireKnownEngines("ASR_PAD_MS_BY_ENGINE", c.padMsByEngine().keySet());
    requireKnownEngines("ASR_NORMALIZE_ENGINES", c.normalizeEngines());
    float target = c.normalizeTargetDbfs(), maxGain = c.normalizeMaxGainDb(), peak = c.normalizePeak();
    if (!Float.isFinite(target) || target > 0) throw new IllegalStateException("ASR_NORMALIZE_TARGET_DBFS must be a finite number <= 0: " + target);
    if (!Float.isFinite(maxGain) || maxGain < 0) throw new IllegalStateException("ASR_NORMALIZE_MAX_GAIN_DB must be a finite number >= 0: " + maxGain);
    if (!Float.isFinite(peak) || peak <= 0 || peak > 1) throw new IllegalStateException("ASR_NORMALIZE_PEAK must be in (0, 1]: " + peak);
  }

  private static void requireKnownEngines(String variable, java.util.Collection<String> names) {
    for (String e : names)
      if (!SherpaEngine.DIRS.containsKey(e))
        throw new IllegalStateException("Unknown engine: " + e + " in " + variable + " (known: " + SherpaEngine.DIRS.keySet() + ")");
  }
}

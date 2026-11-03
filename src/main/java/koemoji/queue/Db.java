package koemoji.queue;

import koemoji.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

/** capture と worker のプロセスが共有する、SQLite によるジョブキュー。WAL を使い、ジョブの取得はアトミックに行う。 */
public final class Db {
  private static final String DEFAULT_ENGINE = "";

  public record Job(long id, String utteranceId, int revision, boolean isFinal, String audioPath, long snapshotBytes, int retryCount) {}
  public record Done(long id, String utteranceId, String engine, int revision, boolean isFinal, String status, String text,
      double logProbabilitySum, int scoredTokenCount, long createdAt) {}

  public interface Sql<T> { T run(Connection c) throws SQLException; }

  private final String url;

  public Db(Config cfg) {
    try {
      mkdirs(cfg);
    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    url = "jdbc:sqlite:" + cfg.queueDb();
    tx(c -> {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("""
            CREATE TABLE IF NOT EXISTS jobs(
              job_id INTEGER PRIMARY KEY AUTOINCREMENT, utterance_id TEXT NOT NULL, revision INTEGER NOT NULL,
              is_final INTEGER NOT NULL, audio_path TEXT NOT NULL, snapshot_bytes INTEGER NOT NULL,
              engine TEXT NOT NULL, status TEXT NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0,
              next_run_at INTEGER NOT NULL, created_at INTEGER NOT NULL, started_at INTEGER, completed_at INTEGER,
              worker_id TEXT, error TEXT, text TEXT, applied INTEGER NOT NULL DEFAULT 0)""");
        s.execute("CREATE INDEX IF NOT EXISTS jobs_status ON jobs(status, next_run_at)");
        s.execute("CREATE INDEX IF NOT EXISTS jobs_utt ON jobs(utterance_id)");
        s.execute("CREATE INDEX IF NOT EXISTS jobs_path ON jobs(audio_path)");
        s.execute("CREATE INDEX IF NOT EXISTS jobs_unapplied ON jobs(status) WHERE applied=0");
        s.execute("CREATE TABLE IF NOT EXISTS guilds(guild_id INTEGER PRIMARY KEY, channel_id INTEGER NOT NULL)");
        s.execute("CREATE TABLE IF NOT EXISTS guild_channels(guild_id INTEGER NOT NULL, engine TEXT NOT NULL, channel_id INTEGER NOT NULL, PRIMARY KEY(guild_id, engine))");
        s.execute("CREATE TABLE IF NOT EXISTS schema_migrations(name TEXT PRIMARY KEY)");
      }
      try (var check = c.prepareStatement("SELECT 1 FROM schema_migrations WHERE name=?")) {
        check.setString(1, "guild_channels_v1");
        try (var r = check.executeQuery()) {
          if (!r.next()) {
            try (Statement s = c.createStatement()) {
              s.executeUpdate("INSERT OR IGNORE INTO guild_channels(guild_id, engine, channel_id) SELECT guild_id, '', channel_id FROM guilds");
            }
            try (var mark = c.prepareStatement("INSERT INTO schema_migrations(name) VALUES(?)")) {
              mark.setString(1, "guild_channels_v1");
              mark.executeUpdate();
            }
          }
        }
      }
      try (var check = c.prepareStatement("SELECT 1 FROM schema_migrations WHERE name=?")) {
        check.setString(1, "asr_scores_v1");
        try (var r = check.executeQuery()) {
          if (!r.next()) {
            try (Statement s = c.createStatement()) {
              s.executeUpdate("ALTER TABLE jobs ADD COLUMN log_probability_sum REAL NOT NULL DEFAULT 0");
              s.executeUpdate("ALTER TABLE jobs ADD COLUMN scored_token_count INTEGER NOT NULL DEFAULT 0");
            }
            try (var mark = c.prepareStatement("INSERT INTO schema_migrations(name) VALUES(?)")) {
              mark.setString(1, "asr_scores_v1");
              mark.executeUpdate();
            }
          }
        }
      }
      c.commit();
      return null;
    });
  }

  private static void mkdirs(Config cfg) throws IOException {
    Files.createDirectories(cfg.queueDb().toAbsolutePath().getParent());
    Files.createDirectories(cfg.audioDir());
  }

  private Connection conn() throws SQLException {
    Properties p = new Properties();
    p.setProperty("busy_timeout", "10000");
    p.setProperty("journal_mode", "WAL");
    p.setProperty("synchronous", "NORMAL");
    return DriverManager.getConnection(url, p);
  }

  <T> T tx(Sql<T> f) {
    try (Connection c = conn()) { return f.run(c); }
    catch (SQLException e) { throw new IllegalStateException(e); }
  }

  /** 同じ発話・エンジンのキュー済み partial は、より新しいジョブで置き換える。final は捨てない。 */
  public void enqueue(String utt, int rev, boolean fin, String path, long bytes, String engine) {
    tx(c -> {
      c.setAutoCommit(false);
      long now = System.currentTimeMillis();
      try (var u = c.prepareStatement(
          "UPDATE jobs SET status='superseded' WHERE utterance_id=? AND engine=? AND is_final=0 AND status='queued'")) {
        u.setString(1, utt); u.setString(2, engine); u.executeUpdate();
      }
      try (var i = c.prepareStatement("""
          INSERT INTO jobs(utterance_id,revision,is_final,audio_path,snapshot_bytes,engine,status,next_run_at,created_at)
          VALUES(?,?,?,?,?,?,'queued',?,?)""")) {
        i.setString(1, utt); i.setInt(2, rev); i.setInt(3, fin ? 1 : 0); i.setString(4, path);
        i.setLong(5, bytes); i.setString(6, engine); i.setLong(7, now); i.setLong(8, now);
        i.executeUpdate();
      }
      c.commit();
      return null;
    });
  }

  /** ジョブをアトミックに取得する。final を古い順に先に、次に partial を新しい順に取る。 */
  public Optional<Job> claim(String worker, String engine) {
    return tx(c -> {
      long now = System.currentTimeMillis();
      try (var p = c.prepareStatement("""
          UPDATE jobs SET status='processing', worker_id=?, started_at=? WHERE job_id=(
            SELECT job_id FROM jobs WHERE status='queued' AND engine=? AND next_run_at<=?
            ORDER BY is_final DESC, CASE WHEN is_final=1 THEN job_id ELSE -job_id END LIMIT 1)
          RETURNING job_id, utterance_id, revision, is_final, audio_path, snapshot_bytes, retry_count""")) {
        p.setString(1, worker); p.setLong(2, now); p.setString(3, engine); p.setLong(4, now);
        try (var r = p.executeQuery()) {
          return r.next() ? Optional.of(new Job(r.getLong(1), r.getString(2), r.getInt(3), r.getInt(4) == 1,
              r.getString(5), r.getLong(6), r.getInt(7))) : Optional.empty();
        }
      }
    });
  }

  /** ジョブを取得した worker だけが確定できる。reaper に回収された worker の遅れた結果は無視する。 */
  public void complete(long id, String worker, String text) {
    complete(id, worker, text, 0, 0);
  }

  public void complete(long id, String worker, String text, double logProbabilitySum, int scoredTokenCount) {
    tx(c -> {
      try (var p = c.prepareStatement(
          "UPDATE jobs SET status='completed', text=?, log_probability_sum=?, scored_token_count=?, completed_at=? WHERE job_id=? AND status='processing' AND worker_id=?")) {
        p.setString(1, text); p.setDouble(2, logProbabilitySum); p.setInt(3, scoredTokenCount);
        p.setLong(4, System.currentTimeMillis()); p.setLong(5, id); p.setString(6, worker); p.executeUpdate();
      }
      return null;
    });
  }

  /** final は retryMax まで指数バックオフで再試行する。partial は使い捨てなので、そのまま失敗にする。 */
  public void fail(Job j, String worker, String err, int retryMax, int backoffMs) {
    tx(c -> {
      long now = System.currentTimeMillis();
      boolean retry = j.isFinal() && j.retryCount() < retryMax;
      try (var p = c.prepareStatement(retry
          ? "UPDATE jobs SET status='queued', retry_count=retry_count+1, next_run_at=?, error=? WHERE job_id=? AND status='processing' AND worker_id=?"
          : "UPDATE jobs SET status='failed', completed_at=?, error=? WHERE job_id=? AND status='processing' AND worker_id=?")) {
        p.setLong(1, retry ? now + ((long) backoffMs << j.retryCount()) : now);
        p.setString(2, err); p.setLong(3, j.id()); p.setString(4, worker); p.executeUpdate();
      }
      return null;
    });
  }

  /**
   * クラッシュからの回復。processing のまま止まったジョブ(worker の異常終了)はキューへ戻す。
   * すでに retryMax 回使い切ったジョブは失敗にし、worker を落とし続けるジョブが無限に繰り返されないようにする。
   */
  public int requeueStale(long timeoutMs, int retryMax) {
    return tx(c -> {
      long now = System.currentTimeMillis();
      try (var give = c.prepareStatement(
               "UPDATE jobs SET status='failed', completed_at=?, error='worker lost' WHERE status='processing' AND started_at<? AND retry_count>=?");
           var retry = c.prepareStatement(
               "UPDATE jobs SET status='queued', retry_count=retry_count+1 WHERE status='processing' AND started_at<?")) {
        give.setLong(1, now); give.setLong(2, now - timeoutMs); give.setInt(3, retryMax);
        int failed = give.executeUpdate();
        retry.setLong(1, now - timeoutMs);
        return failed + retry.executeUpdate();
      }
    });
  }

  public List<Done> pollDone() {
    return tx(c -> {
      var out = new ArrayList<Done>();
      try (var s = c.createStatement(); var r = s.executeQuery("""
          SELECT job_id, utterance_id, engine, revision, is_final, status, text, log_probability_sum, scored_token_count, created_at FROM jobs
          WHERE applied=0 AND status IN ('completed','failed') ORDER BY job_id""")) {
        while (r.next()) out.add(new Done(r.getLong(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5) == 1,
            r.getString(6), r.getString(7), r.getDouble(8), r.getInt(9), r.getLong(10)));
      }
      return out;
    });
  }

  public void markApplied(Collection<Long> ids) {
    if (ids.isEmpty()) return;
    tx(c -> {
      try (var p = c.prepareStatement("UPDATE jobs SET applied=1 WHERE job_id=?")) {
        for (long id : ids) { p.setLong(1, id); p.addBatch(); }
        p.executeBatch();
      }
      return null;
    });
  }

  /**
   * すべての final が TTL より前に確定した発話を [utteranceId, audioPath] で返す。
   * final のない発話(発話の途中でプロセスが止まった場合)は、最新のジョブが失敗時の TTL より古くなったら回収対象にする。
   */
  public List<String[]> expired(long doneTtlMs, long failedTtlMs) {
    return tx(c -> {
      var out = new ArrayList<String[]>();
      long now = System.currentTimeMillis();
      // 全エンジンの final が確定済みであること。TTL は最後に確定した final から数え、1 つでも失敗していれば長い方を使う
      try (var p = c.prepareStatement("""
          SELECT utterance_id, MIN(audio_path) FROM jobs GROUP BY utterance_id
          HAVING SUM(is_final=1 AND status IN ('queued','processing'))=0 AND CASE
            WHEN SUM(is_final)>0 THEN MAX(CASE WHEN is_final=1 THEN completed_at END)
                 < ? - CASE WHEN SUM(is_final=1 AND status='failed')>0 THEN ? ELSE ? END
            ELSE MAX(created_at) < ? - ? END""")) {
        p.setLong(1, now); p.setLong(2, failedTtlMs); p.setLong(3, doneTtlMs);
        p.setLong(4, now); p.setLong(5, failedTtlMs);
        try (var r = p.executeQuery()) { while (r.next()) out.add(new String[] {r.getString(1), r.getString(2)}); }
      }
      return out;
    });
  }

  public void deleteUtterance(String utt) {
    tx(c -> {
      try (var p = c.prepareStatement("DELETE FROM jobs WHERE utterance_id=?")) { p.setString(1, utt); p.executeUpdate(); }
      return null;
    });
  }

  /** "engine|status" をキーにしたジョブ数。 */
  public Map<String, Long> counts() {
    return tx(c -> {
      var out = new TreeMap<String, Long>();
      try (var s = c.createStatement(); var r = s.executeQuery("SELECT engine, status, COUNT(*) FROM jobs GROUP BY engine, status")) {
        while (r.next()) out.put(r.getString(1) + "|" + r.getString(2), r.getLong(3));
      }
      return out;
    });
  }

  public long pendingJobCount() {
    return tx(c -> {
      try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM jobs WHERE status IN ('queued','processing')")) {
        return r.next() ? r.getLong(1) : 0;
      }
    });
  }

  public long unappliedResultCount() {
    return tx(c -> {
      try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM jobs WHERE applied=0 AND status IN ('completed','failed')")) {
        return r.next() ? r.getLong(1) : 0;
      }
    });
  }

  public boolean knowsPath(String path) {
    return tx(c -> {
      try (var p = c.prepareStatement("SELECT 1 FROM jobs WHERE audio_path=? LIMIT 1")) {
        p.setString(1, path);
        try (var r = p.executeQuery()) { return r.next(); }
      }
    });
  }

  public void register(long guild, long channel) {
    register(guild, DEFAULT_ENGINE, channel);
  }

  public void register(long guild, String engine, long channel) {
    String key = engine == null ? DEFAULT_ENGINE : engine;
    tx(c -> {
      try (var p = c.prepareStatement("INSERT OR REPLACE INTO guild_channels(guild_id, engine, channel_id) VALUES(?,?,?)")) {
        p.setLong(1, guild);
        p.setString(2, key);
        p.setLong(3, channel);
        p.executeUpdate();
      }
      return null;
    });
  }

  public void unregister(long guild) {
    unregister(guild, null);
  }

  public void unregister(long guild, String engine) {
    tx(c -> {
      try (var p = c.prepareStatement(engine == null
          ? "DELETE FROM guild_channels WHERE guild_id=?"
          : "DELETE FROM guild_channels WHERE guild_id=? AND engine=?")) {
        p.setLong(1, guild);
        if (engine != null) p.setString(2, engine);
        p.executeUpdate();
      }
      return null;
    });
  }

  /** default の投稿先チャンネル。サーバーに default の設定がなければ null。 */
  public Long channelOf(long guild) {
    return routeOf(guild, null);
  }

  /** engine 個別の投稿先チャンネル。なければサーバーの default を使う。 */
  public Long routeOf(long guild, String engine) {
    return tx(c -> {
      try (var p = c.prepareStatement("SELECT channel_id FROM guild_channels WHERE guild_id=? AND engine IN (?, ?) ORDER BY CASE WHEN engine=? THEN 0 ELSE 1 END LIMIT 1")) {
        p.setLong(1, guild);
        p.setString(2, engine == null ? DEFAULT_ENGINE : engine);
        p.setString(3, DEFAULT_ENGINE);
        p.setString(4, engine == null ? DEFAULT_ENGINE : engine);
        try (var r = p.executeQuery()) { return r.next() ? r.getLong(1) : null; }
      }
    });
  }

  /** 指定した engine ごとの投稿先チャンネル。投稿先のない engine は含まれない。 */
  public Map<String, Long> routesOf(long guild, Collection<String> engines) {
    return tx(c -> {
      var saved = new HashMap<String, Long>();
      try (var p = c.prepareStatement("SELECT engine, channel_id FROM guild_channels WHERE guild_id=?")) {
        p.setLong(1, guild);
        try (var r = p.executeQuery()) {
          while (r.next()) saved.put(r.getString(1), r.getLong(2));
        }
      }
      var routes = new HashMap<String, Long>();
      for (String engine : engines) {
        Long channel = saved.get(engine);
        if (channel == null) channel = saved.get(DEFAULT_ENGINE);
        if (channel != null) routes.put(engine, channel);
      }
      return routes;
    });
  }

  public boolean hasRoutes(long guild) {
    return tx(c -> {
      try (var p = c.prepareStatement("SELECT 1 FROM guild_channels WHERE guild_id=? LIMIT 1")) {
        p.setLong(1, guild);
        try (var r = p.executeQuery()) { return r.next(); }
      }
    });
  }
}

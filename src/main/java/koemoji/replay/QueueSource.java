package koemoji.replay;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** queue.db から再認識の対象と保存時の認識結果を読む。書き込まない。 */
public final class QueueSource {
  public record Target(String utteranceId, long snapshotBytes, Map<String, String> saved) {}

  private QueueSource() {}

  public static List<Target> read(Path queueDb, List<String> engines) {
    SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + queueDb.toAbsolutePath(), config.toProperties());
         var s = c.createStatement()) {
      var saved = new HashMap<String, Map<String, String>>();
      var wanted = new HashSet<>(engines);
      try (var r = s.executeQuery("""
          SELECT utterance_id, engine, text FROM jobs
          WHERE is_final=1 AND status='completed'
            AND job_id IN (SELECT MAX(job_id) FROM jobs WHERE is_final=1 AND status='completed' GROUP BY utterance_id, engine)""")) {
        while (r.next()) {
          if (!wanted.contains(r.getString(2))) continue;
          String text = r.getString(3);
          saved.computeIfAbsent(r.getString(1), k -> new HashMap<>()).put(r.getString(2), text == null ? "" : text);
        }
      }
      var out = new ArrayList<Target>();
      try (var r = s.executeQuery("""
          SELECT utterance_id, snapshot_bytes FROM jobs
          WHERE is_final=1 AND job_id IN (SELECT MIN(job_id) FROM jobs WHERE is_final=1 GROUP BY utterance_id)
          ORDER BY utterance_id""")) {
        while (r.next()) out.add(new Target(r.getString(1), r.getLong(2), saved.getOrDefault(r.getString(1), Map.of())));
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read queue.db " + queueDb + ": " + e.getMessage(), e);
    }
  }
}

package koemoji.capture;

import koemoji.Config;
import koemoji.queue.Db;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns finished ASR jobs into Discord messages, grouping nearby utterances from the same user, engine, and channel.
 * Stale revisions are ignored and edits coalesce to the newest result.
 */
final class Transcripts {
  private static final Logger log = LoggerFactory.getLogger(Transcripts.class);
  private static final long JOIN_GRACE_MS = 1_200;
  private static final long GROUP_RETENTION_MS = 60_000;
  static final long ACTIVE_UTTERANCE_TIMEOUT_MS = 60_000;

  private static final class Msg {
    Group group;
    int appliedRev;
  }

  private record GroupKey(long guildId, long userId, String engine, long channelId) {}

  private static final class Group {
    volatile Long messageId;
    volatile boolean inflight;
    String stableText = "";
    String activeText = "";
    double stableLogProbabilitySum;
    int stableScoredTokenCount;
    boolean stableScoreAvailable = true;
    double activeLogProbabilitySum;
    int activeScoredTokenCount;
    boolean activeScoreAvailable;
    String activeUtteranceId;
    Utt activeUtt;
    long activeUpdatedAt;
    long lastFinalAt;
    boolean deletePending;
  }

  private static final class Utt {
    final long guildId;
    final long userId;
    final long startedAtMs;
    final Map<String, Long> routes;
    final String name;
    final Map<String, Msg> msgs = new ConcurrentHashMap<>();
    final AtomicInteger remaining;
    Utt(long guildId, long userId, long startedAtMs, Map<String, Long> routes, String name, int engines) {
      this.guildId = guildId; this.userId = userId; this.startedAtMs = startedAtMs;
      this.routes = Map.copyOf(routes); this.name = name;
      remaining = new AtomicInteger(engines);
    }
  }

  private final Config c;
  private final Db db;
  private final LongFunction<MessageChannel> channels;
  private final Map<String, Utt> utts = new ConcurrentHashMap<>();
  private final Map<GroupKey, Group> displayGroups = new ConcurrentHashMap<>();

  private final LongSupplier clock;

  Transcripts(Config c, Db db, LongFunction<MessageChannel> channels) { this(c, db, channels, System::currentTimeMillis); }

  Transcripts(Config c, Db db, LongFunction<MessageChannel> channels, LongSupplier clock) {
    this.c = c; this.db = db; this.channels = channels; this.clock = clock;
  }

  long inflightCount() { return displayGroups.values().stream().filter(group -> group.inflight).count(); }

  /** Captures destination routes before the utterance's first job is enqueued. */
  void register(String utteranceId, long guildId, long userId, long startedAtMs, String userName) {
    var engines = new HashSet<>(c.engines());
    utts.put(utteranceId, new Utt(guildId, userId, startedAtMs, db.routesOf(guildId, engines), userName, engines.size()));
  }

  /** Applies the newest completed revision per (utterance, engine). */
  void apply() {
    var done = db.pollDone();
    long now = clock.getAsLong();
    displayGroups.forEach((key, group) -> {
      if (group.activeUtteranceId != null && !group.inflight && !group.deletePending
          && now - group.activeUpdatedAt > ACTIVE_UTTERANCE_TIMEOUT_MS) {
        log.warn("abandoning utterance {} stuck without a final for guild {}, engine {}, channel {}",
            group.activeUtteranceId, key.guildId(), key.engine(), key.channelId());
        abandonActive(group.activeUtt, key.engine(), group, now);
      }
    });
    displayGroups.forEach((key, group) -> {
      if (group.deletePending && !group.inflight) deleteGroupMessage(key, group);
    });
    displayGroups.entrySet().removeIf(e -> !e.getValue().inflight && e.getValue().activeUtteranceId == null
        && !e.getValue().deletePending && e.getValue().lastFinalAt > 0
        && now - e.getValue().lastFinalAt > GROUP_RETENTION_MS);
    if (done.isEmpty()) return;
    var groups = new LinkedHashMap<String, List<Db.Done>>();
    for (var d : done) groups.computeIfAbsent(d.utteranceId() + "|" + d.engine(), k -> new ArrayList<>()).add(d);
    var consumed = new ArrayList<Long>();
    groups.forEach((key, list) -> {
      String id = list.get(0).utteranceId(), engine = list.get(0).engine();
      Utt u = utts.get(id);
      if (u == null) { list.forEach(d -> consumed.add(d.id())); return; }
      Msg m = u.msgs.computeIfAbsent(engine, k -> new Msg());
      if (m.group != null && m.group.inflight) return;  // rows stay unconsumed; next tick only the newest matters
      Long channelId = u.routes.get(engine);
      if (m.group == null && channelId != null) {
        Group candidate = displayGroups.get(new GroupKey(u.guildId, u.userId, engine, channelId));
        if (candidate != null && (candidate.inflight || candidate.deletePending
            || candidate.activeUtteranceId != null && !candidate.activeUtteranceId.equals(id))) return;
      }
      Db.Done best = list.stream().filter(Db.Done::isFinal).findFirst()
          .orElseGet(() -> list.stream().max(Comparator.comparingInt(Db.Done::revision)).get());
      list.forEach(d -> consumed.add(d.id()));
      if (best.revision() <= m.appliedRev) return;
      if (best.status().equals("failed")) {
        if (best.isFinal()) failFinal(id, u, engine, m, best.createdAt());
        return;
      }
      m.appliedRev = best.revision();
      show(id, u, engine, m, best);
    });
    db.markApplied(consumed);
  }

  private void settle(String id, Utt u) { if (u.remaining.decrementAndGet() <= 0) utts.remove(id); }

  private void failFinal(String id, Utt u, String engine, Msg m, long completedAt) {
    Group group = m.group;
    if (group != null && id.equals(group.activeUtteranceId)) abandonActive(u, engine, group, completedAt);
    settle(id, u);
  }

  /** active 発話を破棄し、表示を確定済みテキストへ戻す (確定済みテキストが無ければメッセージを削除する)。 */
  private void abandonActive(Utt u, String engine, Group group, long at) {
    group.activeText = "";
    group.activeLogProbabilitySum = 0;
    group.activeScoredTokenCount = 0;
    group.activeScoreAvailable = false;
    group.activeUtteranceId = null;
    group.activeUtt = null;
    group.lastFinalAt = at;
    if (group.stableText.isEmpty()) {
      Long channelId = u.routes.get(engine);
      if (group.messageId != null) group.deletePending = true;
      else displayGroups.remove(new GroupKey(u.guildId, u.userId, engine, channelId), group);
    }
    if (!group.stableText.isEmpty() && group.messageId != null) {
      Long channelId = u.routes.get(engine);
      MessageChannel ch = channelId == null ? null : channels.apply(channelId);
      if (ch != null) {
        boolean defaultFormat = c.messageFormat().equals(Config.DEFAULT_MESSAGE_FORMAT);
        String user = defaultFormat ? escapeCodeSpan(u.name) : escapeName(u.name);
        String text = defaultFormat ? escapeCodeSpan(group.stableText) : group.stableText;
        boolean sharesDestination = u.routes.values().stream().filter(channelId::equals).count() > 1;
        String body = renderMessage(engine, user, text, sharesDestination, group, defaultFormat);
        Long messageId = group.messageId;
        group.inflight = true;
        try {
          ch.editMessageById(messageId, body).setAllowedMentions(List.of()).queue(
              x -> group.inflight = false,
              err -> {
                group.inflight = false;
                log.warn("cannot restore final transcript for guild {}, engine {}, channel {}", u.guildId, engine, channelId, err);
              });
        } catch (RuntimeException ex) {
          group.inflight = false;
          log.warn("cannot restore final transcript for guild {}, engine {}, channel {}", u.guildId, engine, channelId, ex);
        }
      }
    }
  }

  private void deleteGroupMessage(GroupKey key, Group group) {
    if (group.messageId == null) {
      displayGroups.remove(key, group);
      group.deletePending = false;
      return;
    }
    MessageChannel ch = channels.apply(key.channelId());
    if (ch == null) return;
    Long messageId = group.messageId;
    group.inflight = true;
    try {
      ch.deleteMessageById(messageId).queue(
          x -> {
            group.messageId = null;
            group.deletePending = false;
            group.inflight = false;
            displayGroups.remove(key, group);
          }, err -> {
            group.inflight = false;
            log.warn("cannot retract failed transcript for guild {}, engine {}, channel {}",
                key.guildId(), key.engine(), key.channelId(), err);
          });
    } catch (RuntimeException ex) {
      group.inflight = false;
      log.warn("cannot retract failed transcript for guild {}, engine {}, channel {}",
          key.guildId(), key.engine(), key.channelId(), ex);
    }
  }

  private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{(user|text|engine)}");

  /** Fills {user}, {text} and {engine} in one pass, so values containing a placeholder are never re-expanded. */
  String render(String engine, String user, String text, boolean sharesDestination) {
    String fmt = c.messageFormat();
    if (sharesDestination && !fmt.contains("{engine}")) fmt = "[{engine}] " + fmt;
    var m = PLACEHOLDER.matcher(fmt);
    var sb = new StringBuilder();
    while (m.find()) {
      String v = switch (m.group(1)) { case "user" -> user; case "text" -> text; default -> engine; };
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
    }
    return m.appendTail(sb).toString();
  }

  private String renderMessage(String engine, String user, String text, boolean sharesDestination,
      Group group, boolean defaultFormat) {
    String body = render(engine, user, text, sharesDestination);
    Double confidence = confidence(group);
    String suffix = confidence == null ? "" : " (" + Math.round(confidence * 100) + "%)";
    int maxBodyLength = 2000 - suffix.length();
    if (body.length() > maxBodyLength) {
      body = defaultFormat ? body.substring(0, maxBodyLength - 1) + "`" : body.substring(0, maxBodyLength);
    }
    return body + suffix;
  }

  private static Double confidence(Group group) {
    if (!group.stableScoreAvailable || !group.activeText.isEmpty() && !group.activeScoreAvailable) return null;
    int tokenCount = group.stableScoredTokenCount + group.activeScoredTokenCount;
    if (tokenCount == 0) return null;
    double logProbabilitySum = group.stableLogProbabilitySum + group.activeLogProbabilitySum;
    double score = Math.exp(logProbabilitySum / tokenCount);
    return Double.isFinite(score) ? Math.max(0, Math.min(1, score)) : null;
  }

  /** Display names are user-controlled: neutralise Markdown (formatting, masked links, headings) in them. */
  static String escapeName(String name) {
    return MarkdownSanitizer.escape(name).replaceAll("([\\[\\]()#>\\-])", "\\\\$1");
  }

  private static String escapeCodeSpan(String value) { return value.replace('`', '｀'); }

  private static String normalizeForDisplay(String value) {
    String text = stripUnicodeWhitespace(value == null ? "" : value);
    text = removeJapaneseCharacterSpaces(text);
    if (containsOnlyWhitespaceOrPunctuation(text)) return "";
    if (isSingleCharacterFullStopFragment(text)) return "";
    while (text.endsWith("。")) {
      text = text.substring(0, text.length() - 1);
    }
    if (containsJapaneseCharacter(text)) {
      while (text.endsWith(".")) text = text.substring(0, text.length() - 1);
    }
    return stripUnicodeWhitespace(text);
  }

  private static String stripUnicodeWhitespace(String text) {
    int start = 0, end = text.length();
    while (start < end) {
      int codePoint = text.codePointAt(start);
      if (!isUnicodeWhitespace(codePoint)) break;
      start += Character.charCount(codePoint);
    }
    while (end > start) {
      int codePoint = text.codePointBefore(end);
      if (!isUnicodeWhitespace(codePoint)) break;
      end -= Character.charCount(codePoint);
    }
    return text.substring(start, end);
  }

  private static String removeJapaneseCharacterSpaces(String text) {
    var result = new StringBuilder(text.length());
    int index = 0;
    while (index < text.length()) {
      int codePoint = text.codePointAt(index);
      if (!isUnicodeWhitespace(codePoint)) {
        result.appendCodePoint(codePoint);
        index += Character.charCount(codePoint);
        continue;
      }
      int start = index;
      while (index < text.length()) {
        codePoint = text.codePointAt(index);
        if (!isUnicodeWhitespace(codePoint)) break;
        index += Character.charCount(codePoint);
      }
      int next = index < text.length() ? text.codePointAt(index) : -1;
      if (result.length() == 0 || !isJapaneseCharacter(result.codePointBefore(result.length()))
          || next < 0 || !isJapaneseCharacter(next)) {
        result.append(text, start, index);
      }
    }
    return result.toString();
  }

  private static boolean containsOnlyWhitespaceOrPunctuation(String text) {
    for (int index = 0; index < text.length();) {
      int codePoint = text.codePointAt(index);
      if (!isUnicodeWhitespace(codePoint) && !isPunctuation(codePoint)) return false;
      index += Character.charCount(codePoint);
    }
    return true;
  }

  private static boolean isSingleCharacterFullStopFragment(String text) {
    int end = text.length();
    boolean hasFullStop = false;
    while (end > 0) {
      int codePoint = text.codePointBefore(end);
      if (codePoint != '.' && codePoint != '。') break;
      hasFullStop = true;
      end -= Character.charCount(codePoint);
    }
    if (!hasFullStop) return false;
    String content = stripUnicodeWhitespace(text.substring(0, end));
    if (content.codePointCount(0, content.length()) != 1) return false;
    return !isPunctuation(content.codePointAt(0));
  }

  private static boolean isUnicodeWhitespace(int codePoint) {
    return (codePoint >= 0x0009 && codePoint <= 0x000D) || codePoint == 0x0020 || codePoint == 0x0085
        || Character.isSpaceChar(codePoint);
  }

  private static boolean containsJapaneseCharacter(String text) {
    for (int index = 0; index < text.length();) {
      int codePoint = text.codePointAt(index);
      if (isJapaneseCharacter(codePoint)) return true;
      index += Character.charCount(codePoint);
    }
    return false;
  }

  private static boolean isPunctuation(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
          Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
          Character.OTHER_PUNCTUATION -> true;
      default -> false;
    };
  }

  private static boolean isJapaneseCharacter(int codePoint) {
    if (codePoint == 0x30FC) return true;
    return switch (Character.UnicodeScript.of(codePoint)) {
      case HIRAGANA, KATAKANA, HAN -> true;
      default -> false;
    };
  }

  private void show(String id, Utt u, String engine, Msg m, Db.Done d) {
    String text = normalizeForDisplay(d.text());
    Long channelId = u.routes.get(engine);
    boolean fin = d.isFinal();
    if (channelId == null) {
      log.warn("no transcript channel configured for guild {}, engine {}; dropping utterance {}", u.guildId, engine, id);
      if (fin) settle(id, u);
      return;
    }
    MessageChannel ch = channels.apply(channelId);
    if (ch == null) {
      log.warn("transcript channel {} not found for guild {}, engine {}; dropping utterance {}", channelId, u.guildId, engine, id);
      if (fin) settle(id, u);
      return;
    }
    if (text.isEmpty() && !fin) return;

    Group group = m.group;
    if (group == null && text.isEmpty()) {
      if (fin) settle(id, u);
      return;
    }
    if (group == null) {
      var key = new GroupKey(u.guildId, u.userId, engine, channelId);
      Group candidate = displayGroups.get(key);
      long gapMs = candidate == null ? Long.MAX_VALUE : u.startedAtMs - candidate.lastFinalAt;
      if (candidate != null && (candidate.activeUtteranceId != null || candidate.lastFinalAt <= 0
          || gapMs < 0 || gapMs > JOIN_GRACE_MS)) {
        displayGroups.remove(key, candidate);
        candidate = null;
      }
      group = candidate == null ? new Group() : candidate;
      displayGroups.put(key, group);
      m.group = group;
    }

    if (!text.isEmpty() && (group.activeUtteranceId == null || !group.activeUtteranceId.equals(id))) {
      group.activeUtteranceId = id;
      group.activeText = "";
      group.activeLogProbabilitySum = 0;
      group.activeScoredTokenCount = 0;
      group.activeScoreAvailable = false;
    }
    if (!text.isEmpty()) {
      group.deletePending = false;  // 破棄後の遅れた結果が届いたら、削除失敗で残った保留を取り消して投稿を消さない
      group.activeUtt = u;
      group.activeUpdatedAt = clock.getAsLong();
      group.activeText = text;
      group.activeLogProbabilitySum = d.logProbabilitySum();
      group.activeScoredTokenCount = d.scoredTokenCount();
      group.activeScoreAvailable = d.scoredTokenCount() > 0 && Double.isFinite(d.logProbabilitySum());
    }

    boolean defaultFormat = c.messageFormat().equals(Config.DEFAULT_MESSAGE_FORMAT);
    String user = defaultFormat ? escapeCodeSpan(u.name) : escapeName(u.name);
    boolean sharesDestination = u.routes.values().stream().filter(channelId::equals).count() > 1;
    String pendingText = joinText(group.stableText, group.activeText);
    if (pendingText.length() > 0) {
      String pendingTranscript = defaultFormat ? escapeCodeSpan(pendingText) : pendingText;
      String pendingBody = render(engine, user, pendingTranscript, sharesDestination);
      if (pendingBody.length() > 2000 && !group.stableText.isEmpty()) {
        Group split = new Group();
        split.activeText = text;
        split.activeLogProbabilitySum = d.logProbabilitySum();
        split.activeScoredTokenCount = d.scoredTokenCount();
        split.activeScoreAvailable = d.scoredTokenCount() > 0 && Double.isFinite(d.logProbabilitySum());
        split.activeUtteranceId = fin ? null : id;
        split.activeUtt = fin ? null : u;
        split.activeUpdatedAt = clock.getAsLong();
        group = split;
        m.group = split;
        displayGroups.put(new GroupKey(u.guildId, u.userId, engine, channelId), split);
      }
    }

    if (fin) {
      if (!text.isEmpty()) {
        group.stableText = joinText(group.stableText, group.activeText);
        if (group.activeScoreAvailable) {
          group.stableLogProbabilitySum += group.activeLogProbabilitySum;
          group.stableScoredTokenCount += group.activeScoredTokenCount;
        } else {
          group.stableScoreAvailable = false;
        }
      }
      group.activeText = "";
      group.activeLogProbabilitySum = 0;
      group.activeScoredTokenCount = 0;
      group.activeScoreAvailable = false;
      group.activeUtteranceId = null;
      group.activeUtt = null;
      group.lastFinalAt = d.createdAt();
    }

    String combined = joinText(group.stableText, group.activeText);
    if (combined.isEmpty()) {
      if (fin) {
        if (group.stableText.isEmpty()) {
          group.deletePending = group.messageId != null;
          if (group.messageId == null) displayGroups.remove(new GroupKey(u.guildId, u.userId, engine, channelId), group);
          else deleteGroupMessage(new GroupKey(u.guildId, u.userId, engine, channelId), group);
        }
        settle(id, u);
      }
      return;
    }

    String transcript = defaultFormat ? escapeCodeSpan(combined) : combined;
    String body = renderMessage(engine, user, transcript, sharesDestination, group, defaultFormat);

    Group currentGroup = group;
    currentGroup.inflight = true;
    Runnable done = () -> { currentGroup.inflight = false; if (fin) settle(id, u); };
    try {
      if (currentGroup.messageId == null) {
        ch.sendMessage(body).setAllowedMentions(List.of()).queue(
            (Message sent) -> { currentGroup.messageId = sent.getIdLong(); done.run(); },
            err -> { log.warn("send failed for guild {}, engine {}, channel {}", u.guildId, engine, channelId, err); done.run(); });
      } else {
        ch.editMessageById(currentGroup.messageId, body).setAllowedMentions(List.of()).queue(
            x -> done.run(), err -> { log.warn("edit failed for guild {}, engine {}, channel {}", u.guildId, engine, channelId, err); done.run(); });
      }
    } catch (RuntimeException ex) {  // JDA checks permissions synchronously (e.g. the bot lost Send Messages)
      log.warn("cannot post for guild {}, engine {}, channel {}", u.guildId, engine, channelId, ex);
      done.run();
    }
  }

  private static String joinText(String left, String right) {
    if (left.isEmpty()) return right;
    if (right.isEmpty()) return left;
    int last = left.codePointBefore(left.length());
    int first = right.codePointAt(0);
    if (isPunctuation(last) || isPunctuation(first)) return left + right;
    return left + "、" + right;
  }
}

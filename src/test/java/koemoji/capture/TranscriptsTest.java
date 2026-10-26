package koemoji.capture;

import static org.junit.jupiter.api.Assertions.*;

import koemoji.Config;
import koemoji.queue.Db;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranscriptsTest {
  static final long CHANNEL = 7, FIRST_MESSAGE = 1000;

  final List<String> calls = new ArrayList<>();
  final List<Runnable> pendingActions = new ArrayList<>();
  final Map<Long, MessageChannel> destinations = new ConcurrentHashMap<>();
  boolean deferActions;
  final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
  Path queueDb;
  Db db;
  Transcripts t;

  /** Channel double recording sends/edits/deletes; every JDA action chain completes synchronously. */
  private MessageChannel channel() {
    return channel(CHANNEL, false);
  }

  private MessageChannel channel(long channelId, boolean fail) {
    return proxy(MessageChannel.class, (self, m, a) -> switch (m.getName()) {
      case "sendMessage" -> action(channelId, "send:" + a[0], FIRST_MESSAGE + (channelId == CHANNEL ? 0 : channelId), m.getReturnType(), fail);
      case "editMessageById" -> action(channelId, "edit:" + a[0] + ":" + a[1], null, m.getReturnType(), fail);
      case "deleteMessageById" -> action(channelId, "delete:" + a[0], null, m.getReturnType(), fail);
      default -> null;
    });
  }

  private Object action(long channelId, String what, Long newId, Class<?> type, boolean fail) {
    boolean[] mentionsOff = {false};
    return proxy(type, (self, m, a) -> switch (m.getName()) {
      case "setAllowedMentions" -> { mentionsOff[0] = ((java.util.Collection<?>) a[0]).isEmpty(); yield self; }
      case "queue" -> {
        calls.add((channelId == CHANNEL ? "" : "channel-" + channelId + ":") + what
            + (what.startsWith("delete") || mentionsOff[0] ? "" : " !mentions-allowed"));
        if (fail) {
          @SuppressWarnings("unchecked") var err = (Consumer<Throwable>) a[1];
          if (err != null) err.accept(new RuntimeException("denied"));
        } else {
          Runnable complete = () -> {
            @SuppressWarnings("unchecked") var ok = (Consumer<Object>) a[0];
            if (ok != null) ok.accept(newId == null ? null : proxy(Message.class,
                (s2, m2, a2) -> m2.getName().equals("getIdLong") ? newId : null));
          };
          if (deferActions) pendingActions.add(complete);
          else complete.run();
        }
        yield null;
      }
      default -> self;
    });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<?> type, Handler h) {
    return (T) Proxy.newProxyInstance(TranscriptsTest.class.getClassLoader(), new Class<?>[] {type},
        (self, m, a) -> h.handle(self, m, a));
  }

  private interface Handler { Object handle(Object self, java.lang.reflect.Method m, Object[] a) throws Throwable; }

  @BeforeEach void setUp() throws Exception { start("a"); }

  private void start(String engines) throws Exception { start(engines, null); }

  private void start(String engines, String format) throws Exception {
    var tmp = Files.createTempDirectory("cwtr");
    queueDb = tmp.resolve("q.db");
    Map<String, String> env = Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", queueDb.toString(), "ASR_ENGINES", engines);
    if (format != null) { env = new java.util.HashMap<>(env); env.put("MESSAGE_FORMAT", format); }
    var c = Config.of(env::get);
    db = new Db(c);
    db.register(1, CHANNEL);
    calls.clear();
    destinations.clear();
    destinations.put(CHANNEL, channel());
    clock.set(System.currentTimeMillis());
    t = new Transcripts(c, db, destinations::get, clock::get);
    register("u1", 1, "bob");
  }

  private void result(String engine, int rev, boolean fin, String text) {
    resultFor("u1", engine, rev, fin, text);
  }

  private void register(String utteranceId, long guildId, String name) {
    t.register(utteranceId, guildId, utteranceId.hashCode(), System.currentTimeMillis(), name);
  }

  private void resultFor(String utteranceId, String engine, int rev, boolean fin, String text) {
    resultFor(utteranceId, engine, rev, fin, text, 0, 0);
  }

  private void resultFor(String utteranceId, String engine, int rev, boolean fin, String text,
      double logProbabilitySum, int scoredTokenCount) {
    db.enqueue(utteranceId, rev, fin, "/x", 1, engine);
    db.complete(db.claim("w", engine).get().id(), "w", text, logProbabilitySum, scoredTokenCount);
    t.apply();
  }

  @Test void displayNamesCannotInjectMarkdown() {
    String e = Transcripts.escapeName("[Admin](https://evil.example) # x");
    assertFalse(e.contains("[Admin]("));
    assertTrue(e.startsWith("\\["));
  }

  @Test void firstResultCreatesTheMessageAndLaterRevisionsEditIt() {
    result("a", 1, false, "hello");
    result("a", 2, false, "hello world");
    result("a", 3, true, "Hello, world.");
    assertEquals(List.of("send:`bob`: `hello`", "edit:1000:`bob`: `hello world`", "edit:1000:`bob`: `Hello, world.`"), calls);
  }

  @Test void scoredPartialsAndFinalsDisplayTheirUpdatedConfidence() {
    resultFor("u1", "a", 1, false, "partial", 2 * Math.log(0.81), 2);
    resultFor("u1", "a", 2, true, "final", Math.log(0.64), 1);

    assertEquals(List.of("send:`bob`: `partial` (81%)", "edit:1000:`bob`: `final` (64%)"), calls);
  }

  @Test void joinedUtterancesAggregateConfidenceByScoredTokenCount() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "first", Math.log(0.81), 1);
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "second", 3 * Math.log(0.64), 3);

    assertEquals(List.of("send:`bob`: `first` (81%)", "edit:1000:`bob`: `first、second` (68%)"), calls);
  }

  @Test void joinedUtterancesOmitConfidenceWhenAnyResultHasNoScore() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "first", Math.log(0.81), 1);
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "second");

    assertEquals(List.of("send:`bob`: `first` (81%)", "edit:1000:`bob`: `first、second`"), calls);
  }

  @Test void closeUtterancesFromOneUserEditTheSameMessage() throws Exception {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "なんか。");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "これ違うな。");

    assertEquals(List.of("send:`bob`: `なんか`", "edit:1000:`bob`: `なんか、これ違うな`"), calls);
  }

  @Test void waitsForAnInFlightGroupRequestBeforeApplyingTheNextUtterance() {
    deferActions = true;
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "first");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "second");

    assertEquals(List.of("send:`bob`: `first`"), calls);
    assertEquals(1, pendingActions.size());
    pendingActions.removeFirst().run();
    t.apply();

    assertEquals(List.of("send:`bob`: `first`", "edit:1000:`bob`: `first、second`"), calls);
  }

  @Test void partialsAndFinalsForTheNextUtteranceUpdateTheJoinedMessage() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "なんか");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, false, "これ");
    resultFor("u2", "a", 2, true, "これ違うな");
    resultFor("u2", "a", 1, false, "古い結果");

    assertEquals(List.of("send:`bob`: `なんか`", "edit:1000:`bob`: `なんか、これ`",
        "edit:1000:`bob`: `なんか、これ違うな`"), calls);
  }

  @Test void emptyFinalRestoresThePreviousFinalText() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "なんか");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, false, "これ");
    resultFor("u2", "a", 2, true, "");

    assertEquals(List.of("send:`bob`: `なんか`", "edit:1000:`bob`: `なんか、これ`",
        "edit:1000:`bob`: `なんか`"), calls);
  }

  @Test void failedFinalRestoresThePreviousFinalText() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "なんか");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, false, "これ");
    db.enqueue("u2", 2, true, "/x", 1, "a");
    db.fail(db.claim("w", "a").orElseThrow(), "w", "failed", 0, 0);
    t.apply();

    assertEquals(List.of("send:`bob`: `なんか`", "edit:1000:`bob`: `なんか、これ`",
        "edit:1000:`bob`: `なんか`"), calls);
  }

  private void stuckPartial(String utteranceId, String text) {
    t.register(utteranceId, 1, 42, System.currentTimeMillis(), "bob");
    resultFor(utteranceId, "a", 1, false, text);
  }

  @Test void anUtteranceStuckWithoutAFinalIsAbandonedAfterTheTimeoutAndItsMessageDeleted() {
    stuckPartial("u1", "first");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "second");
    assertEquals(List.of("send:`bob`: `first`"), calls);

    clock.addAndGet(Transcripts.ACTIVE_UTTERANCE_TIMEOUT_MS + 1);
    t.apply();

    assertEquals(List.of("send:`bob`: `first`", "delete:1000", "send:`bob`: `second`"), calls);
  }

  @Test void anAbandonedUtteranceRestoresThePreviousFinalText() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "なんか");
    stuckPartial("u2", "これ");
    t.register("u3", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u3", "a", 1, true, "next");
    assertEquals(List.of("send:`bob`: `なんか`", "edit:1000:`bob`: `なんか、これ`"), calls);

    clock.addAndGet(Transcripts.ACTIVE_UTTERANCE_TIMEOUT_MS + 1);
    t.apply();

    assertEquals(List.of("send:`bob`: `なんか`", "edit:1000:`bob`: `なんか、これ`", "edit:1000:`bob`: `なんか`",
        "send:`bob`: `next`"), calls);
  }

  @Test void anUtteranceIsNotAbandonedAtExactlyTheTimeout() {
    stuckPartial("u1", "first");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "second");

    clock.addAndGet(Transcripts.ACTIVE_UTTERANCE_TIMEOUT_MS);
    t.apply();

    assertEquals(List.of("send:`bob`: `first`"), calls);
  }

  @Test void aLateFinalAfterAbandonmentStillPosts() {
    stuckPartial("u1", "first");
    clock.addAndGet(Transcripts.ACTIVE_UTTERANCE_TIMEOUT_MS + 1);
    t.apply();
    assertEquals(List.of("send:`bob`: `first`", "delete:1000"), calls);

    resultFor("u1", "a", 2, true, "first done");

    assertEquals(List.of("send:`bob`: `first`", "delete:1000", "send:`bob`: `first done`"), calls);
  }

  @Test void aFailedDeleteLeavingDeletePendingDoesNotDeleteALateFinal() {
    stuckPartial("u1", "first");
    destinations.put(CHANNEL, channel(CHANNEL, true));
    clock.addAndGet(Transcripts.ACTIVE_UTTERANCE_TIMEOUT_MS + 1);
    t.apply();
    resultFor("u1", "a", 2, true, "first done");

    destinations.put(CHANNEL, channel());
    int before = calls.size();
    t.apply();

    assertEquals(before, calls.size());
  }

  @Test void anInflightGroupIsNotAbandoned() {
    deferActions = true;
    stuckPartial("u1", "first");
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "second");

    clock.addAndGet(Transcripts.ACTIVE_UTTERANCE_TIMEOUT_MS + 1);
    t.apply();

    assertEquals(List.of("send:`bob`: `first`"), calls);
  }

  @Test void differentUserIdsNeverShareAMessageEvenWhenNamesMatch() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "hello");
    t.register("u2", 1, 43, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "world");

    assertEquals(List.of("send:`bob`: `hello`", "send:`bob`: `world`"), calls);
  }

  @Test void utterancesOutsideTheJoinWindowCreateANewMessage() throws Exception {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "hello");
    Thread.sleep(1250);
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "world");

    assertEquals(List.of("send:`bob`: `hello`", "send:`bob`: `world`"), calls);
  }

  @Test void combinedMessageOverDiscordLimitStartsANewMessage() {
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u1", "a", 1, true, "x".repeat(1500));
    t.register("u2", 1, 42, System.currentTimeMillis(), "bob");
    resultFor("u2", "a", 1, true, "y".repeat(600));

    assertEquals(2, calls.size());
    assertTrue(calls.get(0).startsWith("send:`bob`: `"));
    assertTrue(calls.get(1).startsWith("send:`bob`: `"));
    assertEquals(1509, calls.get(0).length() - "send:".length());
    assertEquals(609, calls.get(1).length() - "send:".length());
  }

  @Test void defaultFormatKeepsMarkdownPunctuationReadableInCodeSpanNames() {
    register("u1", 1, "foo-bar`baz");
    result("a", 1, true, "hello `there`");
    assertEquals(List.of("send:`foo-bar｀baz`: `hello ｀there｀`"), calls);
  }

  @Test void aLateResultFromAnOlderRevisionIsIgnored() {
    db.enqueue("u1", 1, false, "/x", 1, "a");
    var old = db.claim("w", "a").get();
    result("a", 2, false, "new");
    db.complete(old.id(), "w", "old");
    t.apply();
    assertEquals(List.of("send:`bob`: `new`"), calls);
  }

  @Test void emptyPartialsPostNothingAndAnEmptyFinalRetractsTheMessage() {
    result("a", 1, false, "  ");
    assertEquals(List.of(), calls);
    result("a", 2, false, "um");
    result("a", 3, true, "");
    register("u2", 1, "bob");
    resultFor("u2", "a", 1, false, "maybe");
    resultFor("u2", "a", 2, true, "は。");
    assertEquals(List.of("send:`bob`: `um`", "delete:1000", "send:`bob`: `maybe`", "delete:1000"), calls);
  }

  @Test void punctuationAndSingleCharacterFullStopFragmentsAreSuppressed() {
    result("a", 1, true, "。");
    register("u2", 1, "bob");
    resultFor("u2", "a", 1, true, ".");
    register("u3", 1, "bob");
    resultFor("u3", "a", 1, true, "？");
    register("u4", 1, "bob");
    resultFor("u4", "a", 1, true, "?");
    register("u5", 1, "bob");
    resultFor("u5", "a", 1, true, "は。");
    register("u6", 1, "bob");
    resultFor("u6", "a", 1, true, "😀。。");
    assertEquals(List.of(), calls);
  }

  @Test void whitespaceNormalizationUsesUnicodeWhiteSpace() {
    result("a", 1, true, "\u001Ctext\u001F");
    register("u2", 1, "bob");
    resultFor("u2", "a", 1, true, "漢\u001C字");
    assertEquals(List.of("send:`bob`: `\u001Ctext\u001F`", "send:`bob`: `漢\u001C字`"), calls);
  }

  @Test void displayTextIsNormalizedWithoutChangingTheStoredAsrText() throws Exception {
    String source = "\u00A0頭 の 中 に もう 一人 がいる。\u0085";
    result("a", 1, true, source);
    register("u2", 1, "bob");
    resultFor("u2", "a", 1, true, "コ ー ヒ ー。");
    assertEquals(List.of("send:`bob`: `頭の中にもう一人がいる`", "send:`bob`: `コーヒー`"), calls);

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + queueDb);
         var query = connection.prepareStatement("SELECT text FROM jobs WHERE utterance_id=?")) {
      query.setString(1, "u1");
      try (var rows = query.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(source, rows.getString(1));
      }
    }
  }

  @Test void trailingFullStopsAreRemovedAndQuestionMarksInTextArePreserved() {
    result("a", 1, true, "プレステージ2。。");
    register("u2", 1, "bob");
    resultFor("u2", "a", 1, true, "この単語は何？");
    assertEquals(List.of("send:`bob`: `プレステージ2`", "send:`bob`: `この単語は何？`"), calls);
  }

  @Test void asciiFullStopsAreRemovedOnlyFromJapaneseText() {
    result("a", 1, true, "U.S.");
    register("u2", 1, "bob");
    resultFor("u2", "a", 1, true, "Dr.");
    register("u3", 1, "bob");
    resultFor("u3", "a", 1, true, "これは日本語です.");
    register("u4", 1, "bob");
    resultFor("u4", "a", 1, true, "コ ー ヒ ー.");
    assertEquals(List.of("send:`bob`: `U.S.`", "send:`bob`: `Dr.`", "send:`bob`: `これは日本語です`", "send:`bob`: `コーヒー`"), calls);
  }

  @Test void eachEngineGetsItsOwnMessage() throws Exception {
    start("a,b");
    result("a", 1, true, "one");
    result("b", 1, true, "two");
    assertEquals(List.of("send:[a] `bob`: `one`", "send:[b] `bob`: `two`"), calls);
  }

  @Test void enginesRoutedToDifferentChannelsAreNotLabeled() throws Exception {
    start("a,b");
    db.register(1, "a", 8);
    db.register(1, "b", 9);
    destinations.put(8L, channel(8, false));
    destinations.put(9L, channel(9, false));
    t.register("u1", 1, 42, System.currentTimeMillis(), "bob");

    result("a", 1, true, "one");
    result("b", 1, true, "two");

    assertEquals(List.of("channel-8:send:`bob`: `one`", "channel-9:send:`bob`: `two`"), calls);
  }

  @Test void engineRoutesCanShareAChannel() throws Exception {
    start("a,b");
    db.register(1, "a", CHANNEL);
    db.register(1, "b", CHANNEL);
    register("u1", 1, "bob");

    result("a", 1, true, "one");
    result("b", 1, true, "two");

    assertEquals(List.of("send:[a] `bob`: `one`", "send:[b] `bob`: `two`"), calls);
  }

  @Test void routesAreSnapshottedWhenTheUtteranceIsRegistered() throws Exception {
    start("a");
    db.register(1, "a", 8);
    destinations.put(8L, channel(8, false));

    result("a", 1, false, "partial");
    result("a", 2, true, "final");

    assertEquals(List.of("send:`bob`: `partial`", "edit:" + FIRST_MESSAGE + ":`bob`: `final`"), calls);
  }

  @Test void aMissingEngineRouteDoesNotBlockOtherEngines() throws Exception {
    start("a,b");
    db.unregister(1);
    db.register(1, "a", 8);
    destinations.put(8L, channel(8, false));
    register("u1", 1, "bob");

    result("a", 1, true, "one");
    result("b", 1, true, "two");

    assertEquals(List.of("channel-8:send:" + "`bob`: `one`"), calls);
  }

  @Test void aFailedEngineChannelDoesNotBlockAnotherEngine() throws Exception {
    start("a,b");
    db.unregister(1);
    db.register(1, "a", 8);
    db.register(1, "b", 9);
    destinations.put(8L, channel(8, true));
    destinations.put(9L, channel(9, false));
    register("u1", 1, "bob");

    result("a", 1, true, "one");
    result("b", 1, true, "two");

    assertEquals(List.of("channel-8:send:`bob`: `one`", "channel-9:send:`bob`: `two`"), calls);
  }

  @Test void bodiesAreCappedAtDiscordsLimit() {
    result("a", 1, true, "x".repeat(5000));
    assertEquals(1, calls.size());
    assertEquals(2000, calls.get(0).length() - "send:".length());
    assertTrue(calls.get(0).endsWith("`"));
    assertEquals(4, calls.get(0).chars().filter(c -> c == '`').count());
  }

  @Test void failedFinalPostsNothing() {
    db.enqueue("u1", 1, true, "/x", 1, "a");
    db.fail(db.claim("w", "a").get(), "w", "boom", 0, 0);
    t.apply();
    assertEquals(List.of(), calls);
  }

  @Test void resultsForUnknownUtterancesAreDiscardedWithoutPosting() {
    db.enqueue("other", 1, true, "/x", 1, "a");
    db.complete(db.claim("w", "a").get().id(), "w", "text");
    t.apply();
    assertEquals(List.of(), calls);
    assertTrue(db.pollDone().isEmpty());
  }

  @Test void messagesNeverPingAnyone() {
    result("a", 1, true, "@everyone hi");
    assertEquals(List.of("send:`bob`: `@everyone hi`"), calls);
  }

  @Test void customFormat() throws Exception {
    start("a", "**{user}** ({engine}): {text}");
    result("a", 1, true, "{user} hi");
    assertEquals(List.of("send:**bob** (a): {user} hi"), calls);
  }

  @Test void multipleEnginesPrefixWhenFormatLacksEngine() throws Exception {
    start("a,b", "> {user}: {text}");
    result("a", 1, true, "x");
    assertEquals(List.of("send:[a] > bob: x"), calls);
  }

  @Test void customFormatCanShowEngineForSeparateChannels() throws Exception {
    start("a,b", "{engine}: {text}");
    db.register(1, "a", 8);
    db.register(1, "b", 9);
    destinations.put(8L, channel(8, false));
    destinations.put(9L, channel(9, false));
    register("u1", 1, "bob");

    result("a", 1, true, "one");

    assertEquals(List.of("channel-8:send:a: one"), calls);
  }
}

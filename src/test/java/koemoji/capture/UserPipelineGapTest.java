package koemoji.capture;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import koemoji.Config;
import koemoji.queue.Db;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 受信間隔からの欠落量の推定を、注入した時計と常に発話とみなす VAD で確かめる(モデルは要らない)。 */
class UserPipelineGapTest {
  private static final int PACKET_BYTES = 3840;  // 20 ms
  private static final int[] WARMUP = IntStream.range(0, 10).map(i -> 20).toArray();

  private final AtomicLong clock = new AtomicLong(1_000_000);
  private final List<String> utts = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(UserPipeline.class);
  private Config c;
  private UserPipeline p;

  @BeforeEach void setUp() throws Exception {
    var tmp = Files.createTempDirectory("cwgap");
    var env = Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", tmp.resolve("q.db").toString(), "ASR_ENGINES", "a");
    c = Config.of(env::get);
    Files.createDirectories(c.audioDir());
    p = new UserPipeline(c, new Db(c), "u", utts::add, new UserPipeline.VadDetector() {
      @Override public float compute(float[] samples) { return 1f; }
      @Override public void reset() {}
      @Override public void release() {}
    }, clock::get);
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach void tearDown() { logger.detachAppender(appender); }

  /** 各要素は、そのパケットが前のパケットから何 ms 後に届くか。 */
  private void feed(int... intervalsMs) {
    for (int ms : intervalsMs) {
      clock.addAndGet(ms);
      p.accept(new byte[PACKET_BYTES]);
    }
  }

  private static int[] concat(int[] a, int... b) {
    return IntStream.concat(IntStream.of(a), IntStream.of(b)).toArray();
  }

  private static int[] repeat(int ms, int n) { return IntStream.generate(() -> ms).limit(n).toArray(); }

  private List<String> lossLogs() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("est_lost_ms=")).toList();
  }

  private void assertEstimate(long expectedLostMs) throws Exception {
    p.close();
    assertEquals(1, utts.size());
    String id = utts.get(0);
    long audioMs = Files.size(c.audioDir().resolve(id + ".pcm")) / 32;
    assertEquals(List.of("utterance " + id + " user u audio_ms=" + audioMs + " est_lost_ms=" + expectedLostMs), lossLogs());
  }

  @Test void noLossIsZero() throws Exception {
    feed(concat(WARMUP, repeat(20, 50)));
    assertEstimate(0);
  }

  @Test void threeMissingPacketsAreSixtyMs() throws Exception {
    feed(concat(WARMUP, 80));
    feed(repeat(20, 49));
    assertEstimate(60);
  }

  @Test void aLatePacketFollowedByABunchIsNotCounted() throws Exception {
    feed(concat(WARMUP, 100, 0, 0, 0, 0));
    feed(repeat(20, 45));
    assertEstimate(0);
  }

  @Test void packetsArrivingEarlyDoNotCancelALaterLoss() throws Exception {
    feed(concat(WARMUP, 0, 0, 0, 0, 0, 80));
    feed(repeat(20, 44));
    assertEstimate(60);
  }

  @Test void anIntervalOfExactlyTheGapLimitIsCounted() throws Exception {
    feed(concat(WARMUP, 200));
    feed(repeat(20, 49));
    assertEstimate(180);
  }

  @Test void anIntervalOverTheGapLimitIsSilenceAndNotCounted() throws Exception {
    feed(concat(WARMUP, 201));
    feed(repeat(20, 49));
    assertEstimate(0);
  }

  @Test void aGapBeforeTheUtteranceOpensIsNotCounted() throws Exception {
    feed(20, 20, 80);
    feed(repeat(20, 57));
    assertEstimate(0);
  }

  @Test void aDroppedShortUtteranceLogsNothing() {
    feed(repeat(20, 8));
    p.close();
    assertEquals(List.of(), lossLogs());
  }
}

package koemoji.capture;

import koemoji.Config;
import koemoji.queue.Db;

import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ユーザーごとの音声パイプライン。48 kHz ステレオ big-endian PCM を 16 kHz モノラルにし、Silero VAD で発話を検出して
 * 発話ファイル(追記のみの s16le)へ書き、partial/final のジョブをキューへ登録する。
 * 音声は JDA のスレッド、tick() はスケジューラのスレッドから呼ばれるため、メソッドはすべて synchronized にしている。
 */
public final class UserPipeline implements Closeable {
  public static final int WIN = 512;           // 16 kHz での Silero のウィンドウ長 (32 ms)
  private static final int WIN_MS = 32;
  private static final int GAP_MS = 200;

  private final Config c;
  private final Db db;
  private final BiConsumer<String, Long> onUtterance;
  private final VadDetector vad;
  private final float[] win = new float[WIN];
  private int winFill;
  private final ArrayDeque<short[]> preroll = new ArrayDeque<>();
  private final short[] cur = new short[WIN];

  private static final int DECIMATE = 3;       // 48 kHz から 16 kHz への間引き
  private int resampleSum, resampleCount;

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserPipeline.class);
  private final String name;
  private float maxProb;
  private int winCount;
  private int speechRun, silenceRun;
  private String utt;
  private long utteranceStartedAtMs;
  private Path path;
  private OutputStream out;
  private long bytes, snapBytes;
  private int rev;
  private long lastPartialMs, lastAudioMs;
  private boolean closed;
  private boolean finalizationSucceeded = true;

  public UserPipeline(Config c, Db db, String name, Consumer<String> onUtterance) {
    this(c, db, name, onUtterance, silero(c));
  }

  static UserPipeline withUtteranceStartTime(Config c, Db db, String name, BiConsumer<String, Long> onUtterance) {
    return new UserPipeline(c, db, name, onUtterance, silero(c));
  }

  UserPipeline(Config c, Db db, String name, Consumer<String> onUtterance, VadDetector vad) {
    this(c, db, name, (id, startedAtMs) -> onUtterance.accept(id), vad);
  }

  private UserPipeline(Config c, Db db, String name, BiConsumer<String, Long> onUtterance, VadDetector vad) {
    this.name = name; this.c = c; this.db = db; this.onUtterance = onUtterance;
    this.vad = vad;
  }

  private static VadDetector silero(Config c) {
    var silero = SileroVadModelConfig.builder().setModel(c.vadModel().toString()).setThreshold(c.vadThreshold())
        .setWindowSize(WIN).build();
    var model = new Vad(VadModelConfig.builder().setSileroVadModelConfig(silero).setSampleRate(16000).setNumThreads(1).setDebug(false).build());
    return new VadDetector() {
      @Override public float compute(float[] samples) { return model.compute(samples); }
      @Override public void reset() { model.reset(); }
      @Override public void release() { model.release(); }
    };
  }

  /** @param pcm 48 kHz ステレオ 16 bit の big-endian(JDA の出力形式) */
  public synchronized void accept(byte[] pcm) {
    if (closed) return;  // VAD はネイティブで close() が解放するため、遅れて届いたパケットで触らない
    long now = System.currentTimeMillis();
    if (now - lastAudioMs > GAP_MS) { winFill = 0; resampleSum = 0; resampleCount = 0; }  // 無音の間、Discord は何も送ってこない
    lastAudioMs = now;
    ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.BIG_ENDIAN);
    while (b.remaining() >= 4) {
      resampleSum += (b.getShort() + b.getShort()) / 2;
      if (++resampleCount == DECIMATE) {
        short s = (short) (resampleSum / DECIMATE);
        resampleSum = 0; resampleCount = 0;
        win[winFill] = s / 32768f; cur[winFill] = s;
        if (++winFill == WIN) { winFill = 0; window(); }
      }
    }
  }

  private void window() {
    float prob = vad.compute(win);
    maxProb = Math.max(maxProb, prob);
    if (++winCount % 150 == 0) { log.info("vad {}: maxProb={} (threshold {})", name, maxProb, c.vadThreshold()); maxProb = 0; }
    boolean speech = prob >= c.vadThreshold();
    if (utt == null) {
      preroll.addLast(cur.clone());
      while (preroll.size() > Math.max(c.prerollMs() / WIN_MS, c.vadStartMs() / WIN_MS)) preroll.removeFirst();
      speechRun = speech ? speechRun + 1 : 0;
      if (speechRun >= Math.max(1, c.vadStartMs() / WIN_MS)) {
        if (begin()) for (short[] w : preroll) write(w);
        preroll.clear();
        speechRun = 0;
      }
      return;
    }
    write(cur);
    if (utt == null) return;  // 書き込みに失敗して発話を破棄済み
    silenceRun = speech ? 0 : silenceRun + 1;
    if (silenceRun * WIN_MS >= c.vadEndSilenceMs()) finish();
    else if (bytes >= c.maxUtteranceMs() * 32L) { finish(); begin(); }  // 続きは新しい発話として扱う
  }

  /** @return 発話ファイルを作れなかったら false(開いたままのものは残らない) */
  private boolean begin() {
    String id = UUID.randomUUID().toString();
    Path p = c.audioDir().resolve(id + ".pcm");
    try { out = new BufferedOutputStream(new FileOutputStream(p.toFile())); }
    catch (IOException e) { log.warn("cannot create {}; dropping this utterance", p, e); return false; }
    utt = id; path = p; utteranceStartedAtMs = System.currentTimeMillis();
    bytes = snapBytes = 0; rev = 0; silenceRun = 0; speechRun = 0;
    lastPartialMs = System.currentTimeMillis();
    return true;
  }

  private void write(short[] w) {
    if (utt == null) return;
    try {
      var bb = ByteBuffer.allocate(w.length * 2).order(ByteOrder.LITTLE_ENDIAN);
      bb.asShortBuffer().put(w);
      out.write(bb.array());
      bytes += bb.capacity();
    } catch (IOException e) { abort(e); }
  }

  /**
   * ディスクの障害時に、パケットのたびに失敗せず、書き込みをやめて次の発話を待つ。
   * partial をすでにキューへ登録していれば、書き出し済みの先頭部分に対する final で発話を閉じ、通常の経路で行とファイルを回収する。
   * 登録していなければ、ファイルを参照するものがないので削除する。
   */
  private void abort(IOException e) {
    log.warn("utterance file failed; closing utterance {}", utt, e);
    try { out.close(); } catch (IOException ignored) {}
    try {
      if (rev > 0) { bytes = snapBytes; enqueueAll(true); }
      else path.toFile().delete();
    } catch (RuntimeException ex) {
      log.warn("could not enqueue a final for utterance {}", utt, ex);
      finalizationSucceeded = false;
    } finally {
      utt = null; speechRun = 0;
      vad.reset();
    }
  }

  private boolean finish() {
    try { out.close(); } catch (IOException e) { abort(e); finalizationSucceeded = false; return false; }
    try {
      if (bytes < c.minUtteranceMs() * 32L) path.toFile().delete();
      else enqueueAll(true);
    } catch (RuntimeException e) {
      log.warn("enqueue failed; dropping utterance {}", utt, e);
      finalizationSucceeded = false;
    } finally {
      utt = null; speechRun = 0;
      vad.reset();
    }
    return finalizationSucceeded;
  }

  /** 設定されたエンジンごとに 1 つずつジョブを登録する(エンジン間の比較用)。リビジョンはエンジン間で共通。 */
  private void enqueueAll(boolean fin) {
    if (rev == 0) onUtterance.accept(utt, utteranceStartedAtMs);  // ジョブができてから、発話を登録する
    rev++;
    for (String e : c.engines()) db.enqueue(utt, rev, fin, path.toString(), bytes, e);
  }

  /** 定期的に呼ばれる。partial のジョブを登録し、ユーザーのパケットが途絶えたら発話を終える。 */
  public synchronized void tick() {
    if (utt == null) return;
    long now = System.currentTimeMillis();
    if (now - lastAudioMs >= c.vadEndSilenceMs()) { finish(); return; }
    if (now - lastPartialMs >= c.partialMs() && bytes > snapBytes) {
      try { out.flush(); } catch (IOException e) { abort(e); return; }
      snapBytes = bytes; lastPartialMs = now;
      enqueueAll(false);
    }
  }

  /** 発話が開いておらず、idleMs 以上音声が届いていなければ true。パイプラインを解放してよい。 */
  public synchronized boolean idle(long idleMs) {
    return utt == null && System.currentTimeMillis() - lastAudioMs > idleMs;
  }

  @Override public synchronized void close() {
    closeForDrain();
  }

  synchronized boolean closeForDrain() {
    if (closed) return finalizationSucceeded;
    closed = true;
    try { if (utt != null) finish(); }
    finally { vad.release(); }
    return finalizationSucceeded;
  }

  interface VadDetector {
    float compute(float[] samples);
    void reset();
    void release();
  }
}

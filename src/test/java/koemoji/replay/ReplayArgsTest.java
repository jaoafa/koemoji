package koemoji.replay;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayArgsTest {
  @TempDir Path tmp;
  Path audio, queueDb, reference;

  @BeforeEach void setUp() throws Exception {
    audio = Files.createDirectory(tmp.resolve("audio"));
    queueDb = Files.createFile(tmp.resolve("queue.db"));
    reference = Files.createFile(tmp.resolve("reference.tsv"));
  }

  private static Function<String, String> env(Map<String, String> m) { return m::get; }

  private void assertRejected(String[] args, String... mentioned) {
    var e = assertThrows(IllegalArgumentException.class, () -> ReplayArgs.parse(args));
    for (String s : mentioned) assertTrue(e.getMessage().contains(s), "'" + s + "' not in: " + e.getMessage());
  }

  private String[] withAudio(String... rest) {
    var all = new java.util.ArrayList<String>(List.of("--audio-dir", audio.toString()));
    all.addAll(List.of(rest));
    return all.toArray(String[]::new);
  }

  @Test void minimalArgumentsGetDefaults() {
    var a = ReplayArgs.parse(withAudio("--engines", "sensevoice"));
    assertEquals(audio, a.audioDir());
    assertEquals(List.of("sensevoice"), a.engines());
    assertNull(a.queueDb());
    assertNull(a.reference());
    assertNull(a.padMs());
    assertNull(a.normalize());
    assertNull(a.limit());
    assertEquals(20, a.top());
    assertEquals(Path.of("replay-out"), a.outDir());
  }

  @Test void everyOptionIsParsed() {
    var out = tmp.resolve("out");
    var a = ReplayArgs.parse(withAudio("--engines", " sensevoice , reazon-ja ,", "--queue-db", queueDb.toString(),
        "--reference", reference.toString(), "--pad-ms", "0,300", "--normalize", "on,off", "--limit", "5", "--top", "3",
        "--out", out.toString()));
    assertEquals(List.of("sensevoice", "reazon-ja"), a.engines());
    assertEquals(queueDb, a.queueDb());
    assertEquals(reference, a.reference());
    assertEquals(List.of(0, 300), a.padMs());
    assertEquals(List.of(true, false), a.normalize());
    assertEquals(5, a.limit());
    assertEquals(3, a.top());
    assertEquals(out, a.outDir());
  }

  @Test void parseDoesNotCreateTheOutputDirectory() {
    var out = tmp.resolve("out");
    ReplayArgs.parse(withAudio("--engines", "sensevoice", "--out", out.toString()));
    assertFalse(Files.exists(out));
  }

  @Test void anEmptyExistingOutputDirectoryIsAccepted() throws Exception {
    var out = Files.createDirectory(tmp.resolve("empty"));
    assertEquals(out, ReplayArgs.parse(withAudio("--engines", "sensevoice", "--out", out.toString())).outDir());
  }

  @Test void requiredArgumentsMustBeGiven() {
    assertRejected(new String[] {"--engines", "sensevoice"}, "--audio-dir");
    assertRejected(withAudio(), "--engines");
  }

  @Test void unknownAndValuelessAndRepeatedArgumentsAreRejected() {
    assertRejected(withAudio("--engines", "sensevoice", "--bogus", "1"), "--bogus");
    assertRejected(withAudio("--engines"), "--engines");
    assertRejected(withAudio("--engines", "sensevoice", "--engines", "reazon-ja"), "--engines");
  }

  @Test void missingPathsAreRejectedWithTheirValue() {
    var missing = tmp.resolve("nope").toString();
    assertRejected(new String[] {"--audio-dir", missing, "--engines", "sensevoice"}, "--audio-dir", missing);
    assertRejected(withAudio("--engines", "sensevoice", "--queue-db", missing), "--queue-db", missing);
    assertRejected(withAudio("--engines", "sensevoice", "--reference", missing), "--reference", missing);
    assertRejected(new String[] {"--audio-dir", queueDb.toString(), "--engines", "sensevoice"}, "--audio-dir", queueDb.toString());
  }

  @Test void enginesMustBeKnownUniqueAndNonEmpty() {
    assertRejected(withAudio("--engines", "sensevoice,nope"), "--engines", "nope");
    assertRejected(withAudio("--engines", "sensevoice,sensevoice"), "--engines", "sensevoice");
    assertRejected(withAudio("--engines", " , "), "--engines");
  }

  @Test void padMsMustBeUniqueNonNegativeIntegers() {
    assertRejected(withAudio("--engines", "sensevoice", "--pad-ms", "-1"), "--pad-ms", "-1");
    assertRejected(withAudio("--engines", "sensevoice", "--pad-ms", "x"), "--pad-ms", "x");
    assertRejected(withAudio("--engines", "sensevoice", "--pad-ms", "0,0"), "--pad-ms", "0");
    assertRejected(withAudio("--engines", "sensevoice", "--pad-ms", ","), "--pad-ms");
    assertRejected(withAudio("--engines", "sensevoice", "--pad-ms", "60001"), "--pad-ms", "60001");
    assertEquals(List.of(60000), ReplayArgs.parse(withAudio("--engines", "sensevoice", "--pad-ms", "60000")).padMs());
  }

  @Test void normalizeMustBeOffOrOnWithoutDuplicates() {
    assertRejected(withAudio("--engines", "sensevoice", "--normalize", "maybe"), "--normalize", "maybe");
    assertRejected(withAudio("--engines", "sensevoice", "--normalize", "off,off"), "--normalize", "off");
  }

  @Test void limitAndTopMustBePositiveIntegers() {
    assertRejected(withAudio("--engines", "sensevoice", "--limit", "0"), "--limit", "0");
    assertRejected(withAudio("--engines", "sensevoice", "--limit", "x"), "--limit", "x");
    assertRejected(withAudio("--engines", "sensevoice", "--top", "0"), "--top", "0");
  }

  @Test void outputDirectoryMustNotHoldAnything() throws Exception {
    var busy = Files.createDirectory(tmp.resolve("busy"));
    Files.createFile(busy.resolve("old.txt"));
    assertRejected(withAudio("--engines", "sensevoice", "--out", busy.toString()), "--out", busy.toString());
    var file = Files.createFile(tmp.resolve("file"));
    assertRejected(withAudio("--engines", "sensevoice", "--out", file.toString()), "--out", file.toString());
  }

  private List<String> names(ReplayArgs a, Map<String, String> env) {
    return a.conditions(env(env)).stream().map(ReplayArgs.Condition::name).toList();
  }

  @Test void conditionsAreEnginesTimesPaddingTimesNormalizationThenSavedResults() {
    var a = ReplayArgs.parse(withAudio("--engines", "sensevoice,reazon-ja", "--queue-db", queueDb.toString(),
        "--pad-ms", "0,300", "--normalize", "off,on"));
    assertEquals(List.of(
        "sensevoice/pad0/normoff", "sensevoice/pad0/normon", "sensevoice/pad300/normoff", "sensevoice/pad300/normon",
        "reazon-ja/pad0/normoff", "reazon-ja/pad0/normon", "reazon-ja/pad300/normoff", "reazon-ja/pad300/normon",
        "saved/sensevoice", "saved/reazon-ja"), names(a, Map.of()));
  }

  @Test void conditionsFollowTheOrderOfTheGivenValues() {
    var a = ReplayArgs.parse(withAudio("--engines", "reazon-ja,sensevoice", "--pad-ms", "300,0", "--normalize", "on"));
    assertEquals(List.of("reazon-ja/pad300/normon", "reazon-ja/pad0/normon", "sensevoice/pad300/normon", "sensevoice/pad0/normon"),
        names(a, Map.of()));
  }

  @Test void conditionsDefaultToEachEnginesEnvironmentSettings() {
    var a = ReplayArgs.parse(withAudio("--engines", "sensevoice,reazon-ja"));
    var env = Map.of("ASR_PAD_MS", "300", "ASR_PAD_MS_BY_ENGINE", "reazon-ja:600", "ASR_NORMALIZE_ENGINES", "reazon-ja");
    assertEquals(List.of("sensevoice/pad300/normoff", "reazon-ja/pad600/normon"), names(a, env));
  }

  @Test void givingOnlyPaddingKeepsTheEnvironmentNormalizationPerEngine() {
    var a = ReplayArgs.parse(withAudio("--engines", "sensevoice,reazon-ja", "--pad-ms", "0,300"));
    var env = Map.of("ASR_NORMALIZE_ENGINES", "reazon-ja");
    assertEquals(List.of("sensevoice/pad0/normoff", "sensevoice/pad300/normoff", "reazon-ja/pad0/normon", "reazon-ja/pad300/normon"),
        names(a, env));
  }

  @Test void conditionConfigAppliesTheConditionAndKeepsTheRestOfTheEnvironment() {
    var env = env(Map.of("ASR_PAD_MS", "300", "ASR_PAD_MS_BY_ENGINE", "reazon-ja:600", "ASR_NORMALIZE_ENGINES", "sensevoice",
        "ASR_NORMALIZE_TARGET_DBFS", "-23"));
    var on = ReplayArgs.conditionConfig(env, new ReplayArgs.Condition("reazon-ja", 150, true, false));
    assertEquals(150, on.padMsFor("reazon-ja"));
    assertTrue(on.normalizes("reazon-ja"));
    assertFalse(on.normalizes("sensevoice"));
    assertEquals(-23f, on.normalizeTargetDbfs());
    var off = ReplayArgs.conditionConfig(env, new ReplayArgs.Condition("sensevoice", 0, false, false));
    assertEquals(0, off.padMsFor("sensevoice"));
    assertFalse(off.normalizes("sensevoice"));
  }

  @Test void engineConfigRunsOnlyThatEngineAsAWorker() {
    var c = ReplayArgs.engineConfig(env(Map.of("MODE", "capture", "WORKER_ENGINES", "sensevoice:2", "MODELS_DIR", "/models")),
        "reazon-ja");
    assertTrue(c.worker());
    assertEquals(List.of("reazon-ja"), c.workerEngines());
    assertEquals(Path.of("/models"), c.modelsDir());
  }
}

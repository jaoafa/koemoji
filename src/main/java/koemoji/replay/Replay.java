package koemoji.replay;

import koemoji.Config;
import koemoji.asr.AsrEngine;
import koemoji.asr.Models;
import koemoji.asr.Worker;
import koemoji.replay.ReplayArgs.Condition;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/** 保存済みの発話を条件違いで再認識し、結果の差を集計する。 */
public final class Replay {
  public interface EngineFactory { AsrEngine create(String engine, Config c) throws Exception; }

  private static final EngineFactory DEFAULT_FACTORY = (engine, c) -> {
    Models.ensureWorkers(c);
    return AsrEngine.create(engine, c);
  };
  private static final int PROGRESS_EVERY = 20;
  private static final List<String> SKIP_REASONS = List.of("missing file", "empty", "odd bytes", "over snapshot", "invalid id");

  private record Utterance(String id, long bytes, Map<String, String> saved) {
    long durationMs() { return bytes / 32; }
  }

  private Replay() {}

  public static void main(String[] args) {
    System.exit(run(args, System::getenv, DEFAULT_FACTORY, System.out, System.err));
  }

  /** 終了コード: 0 成功、2 引数または入力の誤り、1 それ以外の失敗。 */
  public static int run(String[] argv, Function<String, String> env, EngineFactory factory, PrintStream stdout, PrintStream err) {
    try {
      execute(argv, env, factory, stdout, err);
      return 0;
    } catch (IllegalArgumentException e) {
      err.println("Error: " + e.getMessage());
      return 2;
    } catch (Exception e) {
      err.println("Error: " + e);
      return 1;
    }
  }

  private static void execute(String[] argv, Function<String, String> env, EngineFactory factory, PrintStream stdout, PrintStream err)
      throws Exception {
    ReplayArgs args = ReplayArgs.parse(argv);
    List<Condition> conditions = args.conditions(env);
    List<Utterance> targets = select(args);
    Map<String, String> reference = args.reference() == null ? Map.of() : readReference(args.reference());

    var skips = new LinkedHashMap<String, Integer>();
    SKIP_REASONS.forEach(r -> skips.put(r, 0));
    List<Utterance> replayed = new ArrayList<>();
    for (Utterance u : targets) {
      String reason = skipReason(args, u);
      if (reason == null) replayed.add(u); else skips.merge(reason, 1, Integer::sum);
    }
    int skipped = targets.size() - replayed.size();
    String breakdown = skips.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ", " + b).orElseThrow();
    if (replayed.isEmpty())
      throw new IllegalArgumentException("No utterance can be replayed: targets=" + targets.size() + " (" + breakdown + ")");

    Files.createDirectories(args.outDir());  // 認識に時間がかかるので、書けない出力先は先に失敗させる
    var texts = new LinkedHashMap<String, Map<String, String>>();
    conditions.forEach(c -> texts.put(c.name(), new HashMap<>()));
    for (Utterance u : replayed)
      for (var s : u.saved().entrySet()) {
        var saved = texts.get("saved/" + s.getKey());
        if (saved != null) saved.put(u.id(), s.getValue());
      }
    for (String engine : args.engines()) recognize(args, env, factory, engine, conditions, replayed, texts, err);

    var norm = new HashMap<String, Map<String, String>>();
    texts.forEach((name, byId) -> {
      var n = new HashMap<String, String>();
      byId.forEach((id, text) -> n.put(id, TextDiff.normalize(text)));
      norm.put(name, n);
    });
    var names = List.copyOf(texts.keySet());

    var summary = new StringBuilder();
    summary.append("# replay summary\n\n");
    summary.append("- audio dir: ").append(args.audioDir()).append('\n');
    summary.append(f("- utterances: targets=%d, replayed=%d, skipped=%d (%s)\n", targets.size(), replayed.size(), skipped, breakdown));
    summary.append("\n## Conditions\n\n| # | condition |\n|---|---|\n");
    for (int i = 0; i < names.size(); i++) summary.append(f("| %d | %s |\n", i + 1, names.get(i)));

    summary.append("\n## Per condition\n\n| condition | values | empty | chars | CER % | CER utterances |\n|---|---|---|---|---|---|\n");
    var normReference = new HashMap<String, String>();
    reference.forEach((id, text) -> normReference.put(id, TextDiff.normalize(text)));
    for (String name : names) {
      int empty = 0, chars = 0, cerUtterances = 0, distance = 0, referenceChars = 0;
      for (var e : norm.get(name).entrySet()) {
        empty += e.getValue().isEmpty() ? 1 : 0;
        chars += TextDiff.length(e.getValue());
        String ref = normReference.get(e.getKey());
        if (ref != null && !ref.isEmpty()) {
          cerUtterances++;
          distance += TextDiff.distance(ref, e.getValue());
          referenceChars += TextDiff.length(ref);
        }
      }
      String cer = cerUtterances == 0 ? "-" : f("%.2f", 100.0 * distance / referenceChars);
      summary.append(f("| %s | %d | %d | %d | %s | %s |\n", name, norm.get(name).size(), empty, chars, cer,
          cerUtterances == 0 ? "-" : Integer.toString(cerUtterances)));
    }

    var maxDisagreement = new TreeMap<String, Double>();
    summary.append("\n## Pairwise disagreement\n\n| condition A | condition B | mean disagreement | utterances |\n|---|---|---|---|\n");
    for (int i = 0; i < names.size(); i++)
      for (int j = i + 1; j < names.size(); j++) {
        double sum = 0;
        int n = 0;
        for (var a : norm.get(names.get(i)).entrySet()) {
          String b = norm.get(names.get(j)).get(a.getKey());
          if (b == null) continue;
          double d = TextDiff.disagreement(a.getValue(), b);
          sum += d;
          n++;
          maxDisagreement.merge(a.getKey(), d, Math::max);
        }
        summary.append(f("| %s | %s | %s | %d |\n", names.get(i), names.get(j), n == 0 ? "-" : f("%.4f", sum / n), n));
      }

    var durations = new HashMap<String, Long>();
    replayed.forEach(u -> durations.put(u.id(), u.durationMs()));
    List<String> top = maxDisagreement.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
        .limit(args.top()).map(Map.Entry::getKey).toList();
    summary.append(f("\n## Top %d utterances by disagreement\n\n| utterance_id | max disagreement | duration ms |\n|---|---|---|\n", args.top()));
    for (String id : top) summary.append(f("| %s | %.4f | %d |\n", id, maxDisagreement.get(id), durations.get(id)));

    var utterances = new StringBuilder("utterance_id\tduration_ms\tcondition\ttext\n");
    for (Utterance u : replayed)
      for (String name : names) {
        String text = texts.get(name).get(u.id());
        if (text != null) utterances.append(u.id()).append('\t').append(u.durationMs()).append('\t').append(name).append('\t').append(oneLine(text)).append('\n');
      }
    var disagreements = new StringBuilder("utterance_id\tmax_disagreement\tcondition\ttext\n");
    for (String id : top)
      for (String name : names) {
        String text = texts.get(name).get(id);
        if (text != null) disagreements.append(id).append('\t').append(f("%.4f", maxDisagreement.get(id))).append('\t').append(name).append('\t').append(oneLine(text)).append('\n');
      }

    Files.writeString(args.outDir().resolve("summary.md"), summary, StandardCharsets.UTF_8);
    Files.writeString(args.outDir().resolve("utterances.tsv"), utterances, StandardCharsets.UTF_8);
    Files.writeString(args.outDir().resolve("disagreements.tsv"), disagreements, StandardCharsets.UTF_8);
    stdout.print(summary);
  }

  private static void recognize(ReplayArgs args, Function<String, String> env, EngineFactory factory, String engine,
                                List<Condition> conditions, List<Utterance> replayed, Map<String, Map<String, String>> texts,
                                PrintStream err) {
    var mine = conditions.stream().filter(c -> !c.saved() && c.engine().equals(engine)).toList();
    var configs = new HashMap<String, Config>();
    mine.forEach(c -> configs.put(c.name(), ReplayArgs.conditionConfig(env, c)));
    try (AsrEngine asr = create(factory, engine, env)) {
      int done = 0;
      for (Utterance u : replayed) {
        float[] samples = readAudio(args, u);
        for (Condition c : mine) {
          try {
            texts.get(c.name()).put(u.id(), asr.recognizeFinal(Worker.preprocess(samples, configs.get(c.name()), engine)).text());
          } catch (RuntimeException e) {
            throw new IllegalStateException("Recognition failed for utterance " + u.id() + " under " + c.name() + ": " + e, e);
          }
        }
        if (++done % PROGRESS_EVERY == 0) err.println(engine + ": " + done + "/" + replayed.size());
      }
    }
  }

  private static AsrEngine create(EngineFactory factory, String engine, Function<String, String> env) {
    try {
      return factory.create(engine, ReplayArgs.engineConfig(env, engine));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot create engine " + engine + ": " + e, e);
    }
  }

  private static float[] readAudio(ReplayArgs args, Utterance u) {
    try {
      return Worker.read(audioPath(args, u.id()).toString(), u.bytes());
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read audio of utterance " + u.id() + ": " + e, e);
    }
  }

  private static Path audioPath(ReplayArgs args, String id) { return args.audioDir().resolve(id + ".pcm"); }

  private static List<Utterance> select(ReplayArgs args) throws IOException {
    List<Utterance> all = new ArrayList<>();
    if (args.queueDb() != null) {
      for (var t : QueueSource.read(args.queueDb(), args.engines())) all.add(new Utterance(t.utteranceId(), t.snapshotBytes(), t.saved()));
    } else {
      try (Stream<Path> files = Files.list(args.audioDir())) {
        for (Path p : (Iterable<Path>) files::iterator) {
          String name = p.getFileName().toString();
          if (name.endsWith(".pcm") && Files.isRegularFile(p))
            all.add(new Utterance(name.substring(0, name.length() - 4), Files.size(p), Map.of()));
        }
      }
      all.sort(Comparator.comparing(Utterance::id));
    }
    return args.limit() == null || all.size() <= args.limit() ? all : all.subList(0, args.limit());
  }

  private static String skipReason(ReplayArgs args, Utterance u) throws IOException {
    String id = u.id();
    if (id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) return "invalid id";
    Path path = audioPath(args, id);
    if (!Files.isRegularFile(path)) return "missing file";
    if (u.bytes() <= 0) return "empty";
    if (u.bytes() % 2 != 0) return "odd bytes";
    if (args.queueDb() != null && u.bytes() > Files.size(path)) return "over snapshot";
    return null;
  }

  private static Map<String, String> readReference(Path file) throws IOException {
    var out = new HashMap<String, String>();
    var firstLine = new HashMap<String, Integer>();
    List<String> lines;
    try { lines = Files.readAllLines(file, StandardCharsets.UTF_8); }
    catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("--reference must be UTF-8: " + file, e); }
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) continue;
      int tab = line.indexOf('\t');
      if (tab < 0) throw new IllegalArgumentException("--reference line " + (i + 1) + " has no TAB between utterance_id and text: " + file);
      String id = line.substring(0, tab);
      Integer first = firstLine.put(id, i + 1);
      if (first != null)
        throw new IllegalArgumentException("--reference line " + (i + 1) + " repeats utterance_id " + id + " (first at line " + first + ")");
      out.put(id, line.substring(tab + 1));
    }
    return out;
  }

  private static String oneLine(String s) { return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '); }

  private static String f(String format, Object... args) { return String.format(Locale.ROOT, format, args); }
}

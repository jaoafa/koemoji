package koemoji.replay;

import koemoji.Config;
import koemoji.asr.SherpaEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** padMs と normalize は、省略されたら null(そのエンジンの環境設定を使う)。 */
public record ReplayArgs(Path audioDir, List<String> engines, Path queueDb, Path reference,
                         List<Integer> padMs, List<Boolean> normalize, Integer limit, int top, Path outDir) {

  public record Condition(String engine, int padMs, boolean normalize, boolean saved) {
    public String name() {
      return saved ? "saved/" + engine : engine + "/pad" + padMs + "/norm" + (normalize ? "on" : "off");
    }
  }

  private static final Set<String> OPTIONS =
      Set.of("--audio-dir", "--engines", "--queue-db", "--reference", "--pad-ms", "--normalize", "--limit", "--top", "--out");
  private static final String DEFAULT_OUT = "replay-out";
  /** Worker.pad はミリ秒に 16 を掛けて int で扱うので、桁あふれしない範囲に収める。 */
  private static final int MAX_PAD_MS = 60_000;

  public static ReplayArgs parse(String[] args) {
    Map<String, String> opts = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      String name = args[i];
      if (!OPTIONS.contains(name)) throw new IllegalArgumentException("Unknown argument: " + name);
      if (i + 1 >= args.length) throw new IllegalArgumentException(name + " requires a value");
      if (opts.put(name, args[i + 1]) != null) throw new IllegalArgumentException(name + " was given twice");
    }
    return new ReplayArgs(
        directory("--audio-dir", required(opts, "--audio-dir")),
        engines(required(opts, "--engines")),
        opts.containsKey("--queue-db") ? file("--queue-db", opts.get("--queue-db")) : null,
        opts.containsKey("--reference") ? file("--reference", opts.get("--reference")) : null,
        opts.containsKey("--pad-ms") ? pads(opts.get("--pad-ms")) : null,
        opts.containsKey("--normalize") ? normalizations(opts.get("--normalize")) : null,
        opts.containsKey("--limit") ? positive("--limit", opts.get("--limit")) : null,
        opts.containsKey("--top") ? positive("--top", opts.get("--top")) : 20,
        outDir(opts.getOrDefault("--out", DEFAULT_OUT)));
  }

  public List<Condition> conditions(Function<String, String> env) {
    Config base = Config.of(env);
    var out = new ArrayList<Condition>();
    for (String engine : engines) {
      List<Integer> pads = padMs != null ? padMs : List.of(base.padMsFor(engine));
      List<Boolean> norms = normalize != null ? normalize : List.of(base.normalizes(engine));
      for (int pad : pads) for (boolean norm : norms) out.add(new Condition(engine, pad, norm, false));
    }
    if (queueDb != null) for (String engine : engines) out.add(new Condition(engine, 0, false, true));
    return out;
  }

  public static Config conditionConfig(Function<String, String> env, Condition c) {
    return withOverrides(env, Map.of("ASR_PAD_MS", Integer.toString(c.padMs()), "ASR_PAD_MS_BY_ENGINE", "",
        "ASR_NORMALIZE_ENGINES", c.normalize() ? c.engine() : ""));
  }

  /** Models.ensureWorkers は MODE=worker のときの WORKER_ENGINES だけを取得するため、1 エンジンだけの worker 設定にする。 */
  public static Config engineConfig(Function<String, String> env, String engine) {
    return withOverrides(env, Map.of("MODE", "worker", "WORKER_ENGINES", engine));
  }

  private static Config withOverrides(Function<String, String> env, Map<String, String> overrides) {
    return Config.of(key -> overrides.containsKey(key) ? overrides.get(key) : env.apply(key));
  }

  private static String required(Map<String, String> opts, String name) {
    String v = opts.get(name);
    if (v == null) throw new IllegalArgumentException(name + " is required");
    return v;
  }

  private static Path directory(String name, String v) {
    Path p = Path.of(v);
    if (!Files.isDirectory(p)) throw new IllegalArgumentException(name + " must be an existing directory: " + v);
    return p;
  }

  private static Path file(String name, String v) {
    Path p = Path.of(v);
    if (!Files.isRegularFile(p)) throw new IllegalArgumentException(name + " must be an existing file: " + v);
    return p;
  }

  private static Path outDir(String v) {
    Path p = Path.of(v);
    if (!Files.exists(p)) return p;
    if (!Files.isDirectory(p)) throw new IllegalArgumentException("--out must be a directory or not exist: " + v);
    try (Stream<Path> children = Files.list(p)) {
      if (children.findAny().isPresent()) throw new IllegalArgumentException("--out must be empty (it would be overwritten): " + v);
    } catch (IOException e) { throw new UncheckedIOException(e); }
    return p;
  }

  private static List<String> items(String v) {
    return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static List<String> engines(String v) {
    var out = new ArrayList<String>();
    for (String e : items(v)) {
      if (!SherpaEngine.DIRS.containsKey(e))
        throw new IllegalArgumentException("Unknown engine in --engines: " + e + " (known: " + SherpaEngine.DIRS.keySet() + ")");
      if (out.contains(e)) throw new IllegalArgumentException("--engines names an engine twice: " + e);
      out.add(e);
    }
    if (out.isEmpty()) throw new IllegalArgumentException("--engines must name at least one engine: " + v);
    return List.copyOf(out);
  }

  private static List<Integer> pads(String v) {
    var out = new ArrayList<Integer>();
    for (String s : items(v)) {
      int ms;
      try { ms = Integer.parseInt(s); }
      catch (NumberFormatException e) { throw new IllegalArgumentException("--pad-ms must be integers in 0.." + MAX_PAD_MS + ": " + v); }
      if (ms < 0 || ms > MAX_PAD_MS) throw new IllegalArgumentException("--pad-ms must be integers in 0.." + MAX_PAD_MS + ": " + v);
      if (out.contains(ms)) throw new IllegalArgumentException("--pad-ms lists " + ms + " twice: " + v);
      out.add(ms);
    }
    if (out.isEmpty()) throw new IllegalArgumentException("--pad-ms must list at least one value: " + v);
    return List.copyOf(out);
  }

  private static List<Boolean> normalizations(String v) {
    var out = new ArrayList<Boolean>();
    for (String s : items(v)) {
      if (!s.equals("off") && !s.equals("on")) throw new IllegalArgumentException("--normalize must be off or on: " + v);
      boolean on = s.equals("on");
      if (out.contains(on)) throw new IllegalArgumentException("--normalize lists " + s + " twice: " + v);
      out.add(on);
    }
    if (out.isEmpty()) throw new IllegalArgumentException("--normalize must list at least one value: " + v);
    return List.copyOf(out);
  }

  private static int positive(String name, String v) {
    try {
      int n = Integer.parseInt(v.trim());
      if (n >= 1) return n;
    } catch (NumberFormatException ignored) {
      // 下の例外に値を含めて報告する
    }
    throw new IllegalArgumentException(name + " must be an integer >= 1: " + v);
  }
}

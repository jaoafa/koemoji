package koemoji.asr;

import koemoji.Config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 起動時に、足りないモデルを MODELS_DIR へダウンロードする。このディレクトリがそのままキャッシュになる。 */
public final class Models {
  private static final Logger log = LoggerFactory.getLogger(Models.class);
  private static final String BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";
  /** リリースのアーカイブ名がディレクトリ名と異なるエンジン。 */
  private static final Map<String, String> ARCHIVE =
      Map.of("sensevoice", "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17");

  private Models() {}

  public static void ensure(Config c) {
    ensureCapture(c);
    ensureWorkers(c);
  }

  public static void ensureCapture(Config c) {
    try {
      Files.createDirectories(c.modelsDir());
      if (c.capture()) fetchFile(c.vadModel());
    } catch (IOException e) {
      throw new IllegalStateException("model download failed: " + e.getMessage(), e);
    }
  }

  public static void ensureWorkers(Config c) {
    try {
      Files.createDirectories(c.modelsDir());
      if (c.worker()) for (String engine : new TreeSet<>(c.workerEngines())) fetchArchive(c, engine);
    } catch (IOException e) {
      throw new IllegalStateException("model download failed: " + e.getMessage(), e);
    }
  }

  private static void fetchFile(Path target) throws IOException {
    if (Files.exists(target)) return;
    log.info("downloading {}", target.getFileName());
    Files.createDirectories(target.toAbsolutePath().getParent());
    Path tmp = target.resolveSibling(target.getFileName() + ".part");
    try (InputStream in = URI.create(BASE + target.getFileName()).toURL().openStream()) {
      Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
    }
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void fetchArchive(Config c, String engine) throws IOException {
    String dir = SherpaEngine.DIRS.get(engine);
    Path target = c.modelsDir().resolve(dir);
    if (Files.isDirectory(target)) return;
    String archive = ARCHIVE.getOrDefault(engine, dir);
    log.info("downloading model {} ({})", engine, archive);
    // 展開先の隣で展開し、最後に名前を変える。ダウンロードが中断しても、中途半端なキャッシュが残らない。
    Path tmp = c.modelsDir().resolve(dir + ".part");
    deleteTree(tmp);
    try (InputStream in = URI.create(BASE + archive + ".tar.bz2").toURL().openStream()) {
      extract(in, tmp);
    } catch (IOException | RuntimeException ex) {
      try { deleteTree(tmp); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
      throw ex;
    }
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
  }

  /** .tar.bz2 を dest へ展開する。アーカイブ直下の唯一のディレクトリは取り除く。 */
  static void extract(InputStream tarBz2, Path dest) throws IOException {
    Path root = dest.toAbsolutePath().normalize();  // 後段のパストラバーサル検査に、正規化したルートが必要
    try (var in = new TarArchiveInputStream(new BZip2CompressorInputStream(new java.io.BufferedInputStream(tarBz2)))) {
      for (TarArchiveEntry e; (e = in.getNextEntry()) != null; ) {
        Path rel = Path.of(e.getName());
        if (rel.getNameCount() < 2) continue;
        Path out = root.resolve(rel.subpath(1, rel.getNameCount())).normalize();
        if (!out.startsWith(root)) throw new IOException("unsafe path in archive: " + e.getName());
        if (e.isDirectory()) Files.createDirectories(out);
        else if (e.isFile()) { Files.createDirectories(out.getParent()); Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
      }
    }
  }

  private static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p)) return;
    try (var w = Files.walk(p)) { for (Path f : w.sorted(Comparator.reverseOrder()).toList()) Files.delete(f); }
  }
}

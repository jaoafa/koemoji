package koemoji.asr;

import static org.junit.jupiter.api.Assertions.*;

import koemoji.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;

class ModelsTest {
  private static byte[] archive(String... namesAndContents) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(new BZip2CompressorOutputStream(bytes))) {
      for (int i = 0; i < namesAndContents.length; i += 2) {
        String name = namesAndContents[i];
        var e = new TarArchiveEntry(name);
        byte[] data = namesAndContents[i + 1] == null ? new byte[0] : namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8);
        if (namesAndContents[i + 1] != null) e.setSize(data.length);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  @Test void extractDropsTheTopLevelDirectory() throws Exception {
    Path dest = Files.createTempDirectory("cwm").resolve("model");
    Models.extract(new ByteArrayInputStream(archive("top/", null, "top/tokens.txt", "abc", "top/sub/a.onnx", "x")), dest);
    assertEquals("abc", Files.readString(dest.resolve("tokens.txt")));
    assertTrue(Files.exists(dest.resolve("sub/a.onnx")));
    try (var ls = Files.list(dest)) {
      assertEquals(2, ls.count(), "no stray copy of the top-level directory");
    }
  }

  @Test void extractRejectsPathsEscapingTheDestination() throws Exception {
    Path dest = Files.createTempDirectory("cwm").resolve("model");
    assertThrows(IOException.class, () -> Models.extract(new ByteArrayInputStream(archive("top/../../evil.txt", "x")), dest));
    assertFalse(Files.exists(dest.getParent().resolve("evil.txt")));
  }

  @Test void existingModelsAreNotDownloadedAgain() throws Exception {
    Path dir = Files.createTempDirectory("cwm");
    Files.createDirectories(dir.resolve("sensevoice"));
    Files.writeString(dir.resolve("silero_vad.onnx"), "stub");
    var env = Map.of("MODELS_DIR", dir.toString(), "VAD_MODEL", dir.resolve("silero_vad.onnx").toString(), "ASR_ENGINES", "sensevoice");
    assertDoesNotThrow(() -> Models.ensure(Config.of(env::get)));
    assertEquals("stub", Files.readString(dir.resolve("silero_vad.onnx")));
  }

  @Test void workerOnlyModeSkipsTheVadModel() throws Exception {
    Path dir = Files.createTempDirectory("cwm");
    Files.createDirectories(dir.resolve("sensevoice"));
    var env = Map.of("MODE", "worker", "MODELS_DIR", dir.toString(), "VAD_MODEL", dir.resolve("silero_vad.onnx").toString());
    Models.ensure(Config.of(env::get));
    assertFalse(Files.exists(dir.resolve("silero_vad.onnx")));
  }

  @Test void extractAcceptsADestinationThatIsNotNormalized() throws Exception {
    Path base = Files.createTempDirectory("cwm");
    Path dest = base.resolve("x/../model");  // MODELS_DIR=./models のような相対表記
    Models.extract(new ByteArrayInputStream(archive("top/", null, "top/tokens.txt", "abc")), dest);
    assertEquals("abc", Files.readString(base.resolve("model/tokens.txt")));
  }
}

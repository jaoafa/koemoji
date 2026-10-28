package koemoji.asr;

import koemoji.Config;

import static org.junit.jupiter.api.Assertions.*;

import com.k2fsa.sherpa.onnx.WaveReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * install-sherpa.sh が入れるネイティブライブラリが、CTC 系エンジンの確信度を返すことを確かめる。
 * REQUIRE_MODEL_TESTS=1 のときはモデルを取得して必ず実行し、そうでなければモデルがないときにスキップする。
 */
class CtcConfidenceTest {
  private static final boolean REQUIRED = "1".equals(System.getenv("REQUIRE_MODEL_TESTS"));

  @ParameterizedTest
  @CsvSource({"parakeet-ja,test_ja_1.wav", "sensevoice,ja.wav"})
  void ctcEngineReturnsAFiniteNegativeAverageLogProbability(String engine, String wav) {
    var c = Config.of(Map.of("MODE", "worker", "WORKER_ENGINES", engine)::get);
    Path dir = c.modelsDir().resolve(SherpaEngine.DIRS.get(engine));
    if (REQUIRED) Models.ensureWorkers(c);
    else Assumptions.assumeTrue(Files.isDirectory(dir), "model not downloaded: " + dir);

    float[] samples = new WaveReader(dir.resolve("test_wavs").resolve(wav).toString()).getSamples();
    try (AsrEngine asr = AsrEngine.create(engine, c)) {
      AsrEngine.Result result = asr.recognizeFinal(samples);
      assertFalse(result.text().isBlank(), engine + " recognized no text");
      assertTrue(result.scoredTokenCount() > 0, engine + " returned no confidence: " + result);
      double average = result.logProbabilitySum() / result.scoredTokenCount();
      assertTrue(average <= 0 && average > -1, engine + " average log probability out of range: " + average);
    }
  }
}

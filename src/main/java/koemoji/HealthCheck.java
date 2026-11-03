package koemoji;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Docker HEALTHCHECK の入口。/health が 200 を返すか、ヘルスサーバーが無効(HEALTH_PORT <= 0)なら終了コード 0 を返す。 */
public final class HealthCheck {
  public static void main(String[] a) throws Exception {
    System.exit(check(Config.fromEnv().healthPort()));
  }

  /** HEALTH_PORT に対する終了コード。ヘルスサーバーが無効な場合は確認するものがないので 0 を返す。 */
  static int check(int port) throws Exception {
    if (port <= 0) return 0;
    var res = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(), HttpResponse.BodyHandlers.ofString());
    return res.statusCode() == 200 ? 0 : 1;
  }
}

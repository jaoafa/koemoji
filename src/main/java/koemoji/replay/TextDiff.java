package koemoji.replay;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** 認識結果の比較に使う正規化と距離。書記の揺れを差として数えないために、比較の前に正規化する。 */
public final class TextDiff {
  private static final Pattern DROPPED = Pattern.compile("[\\p{P}\\p{S}\\p{Z}\\p{C}]");

  private TextDiff() {}

  public static String normalize(String s) {
    return DROPPED.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll("").toLowerCase(Locale.ROOT);
  }

  public static int length(String s) { return s.codePointCount(0, s.length()); }

  /** Unicode コードポイント単位のレーベンシュタイン距離。 */
  public static int distance(String a, String b) {
    int[] x = a.codePoints().toArray(), y = b.codePoints().toArray();
    int[] prev = new int[y.length + 1], cur = new int[y.length + 1];
    for (int j = 0; j <= y.length; j++) prev[j] = j;
    for (int i = 1; i <= x.length; i++) {
      cur[0] = i;
      for (int j = 1; j <= y.length; j++)
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1));
      int[] t = prev; prev = cur; cur = t;
    }
    return prev[y.length];
  }

  public static double disagreement(String a, String b) {
    int longest = Math.max(length(a), length(b));
    return longest == 0 ? 0 : (double) distance(a, b) / longest;
  }
}

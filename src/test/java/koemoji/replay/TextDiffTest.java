package koemoji.replay;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextDiffTest {
  @Test void normalizeUnifiesWidthAndCase() {
    assertEquals("abc123", TextDiff.normalize("ＡＢＣ１２３"));
  }

  @Test void normalizeDropsPunctuationSymbolsWhitespaceAndControlCharacters() {
    assertEquals("こんにちは世界", TextDiff.normalize("「こんにちは、世界!」"));
    assertEquals("abcd", TextDiff.normalize("a b\tc\nd"));
    assertEquals("abc", TextDiff.normalize("a+b$c"));
    assertEquals("ab", TextDiff.normalize("a\u0000b"));
  }

  @Test void distanceCountsEditsPerCodePoint() {
    assertEquals(1, TextDiff.distance("abc", "abd"));
    assertEquals(1, TextDiff.distance("あいう", "あう"));
    assertEquals(3, TextDiff.distance("", "abc"));
    assertEquals(3, TextDiff.distance("abc", ""));
    assertEquals(0, TextDiff.distance("abc", "abc"));
    assertEquals(1, TextDiff.distance("𠮷a", "a"));
  }

  @Test void distanceIsSymmetric() {
    assertEquals(TextDiff.distance("kitten", "sitting"), TextDiff.distance("sitting", "kitten"));
    assertEquals(3, TextDiff.distance("kitten", "sitting"));
  }

  @Test void lengthCountsCodePoints() {
    assertEquals(2, TextDiff.length("𠮷a"));
    assertEquals(0, TextDiff.length(""));
  }

  @Test void disagreementDividesTheDistanceByTheLongerLength() {
    assertEquals(0.25, TextDiff.disagreement("abcd", "abxd"));
    assertEquals(0.0, TextDiff.disagreement("", ""));
    assertEquals(1.0, TextDiff.disagreement("ab", ""));
    assertEquals(0.5, TextDiff.disagreement("ab", "abcd"));
  }
}

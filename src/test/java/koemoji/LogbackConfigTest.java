package koemoji;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;

class LogbackConfigTest {
  /** グローバルの context は他のテストが変えうるので、新しい context に logback.xml を読み込んで確かめる。 */
  private static LoggerContext load() throws Exception {
    var ctx = new LoggerContext();
    var cfg = new JoranConfigurator();
    cfg.setContext(ctx);
    cfg.doConfigure(LogbackConfigTest.class.getResource("/logback.xml"));
    return ctx;
  }

  @Test
  void dave_decrypt_failure_loggers_are_error_only() throws Exception {
    var ctx = load();
    try {
      assertEquals(Level.ERROR, ctx.getLogger("club.minnced.discord.jdave.DaveDecryptor").getEffectiveLevel());
      assertEquals(Level.ERROR, ctx.getLogger("net.dv8tion.jda.internal.audio.AudioPacket").getEffectiveLevel());
      assertEquals(Level.INFO, ctx.getLogger("koemoji.Ops").getEffectiveLevel());
      assertEquals(Level.INFO, ctx.getLogger("club.minnced.discord.jdave.Other").getEffectiveLevel());
    } finally {
      ctx.stop();
    }
  }
}

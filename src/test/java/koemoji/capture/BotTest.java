package koemoji.capture;

import static koemoji.capture.Bot.Move.*;
import static koemoji.capture.Bot.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import koemoji.Config;
import koemoji.queue.Db;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Bot.decide(inChannel, leftBotChannel, joinedAny, joinedAfk, botChannelHumans, joinedHumans) の判定を確かめる。 */
class BotTest {
  @Test void idleBotJoinsTheChannelAHumanJoined() {
    assertEquals(JOIN, decide(false, false, true, false, 0, 1));
  }

  @Test void idleBotIgnoresAfkChannelAndLeaves() {
    assertEquals(NONE, decide(false, false, true, true, 0, 1));
    assertEquals(NONE, decide(false, true, false, false, 0, 0));
  }

  @Test void busyBotIgnoresJoinsToOtherChannels() {
    assertEquals(NONE, decide(true, false, true, false, 2, 1));
  }

  @Test void botFollowsHumansMovingToABusierChannelOnly() {
    assertEquals(JOIN, decide(true, true, true, false, 1, 2));
    assertEquals(NONE, decide(true, true, true, false, 2, 2));
    assertEquals(NONE, decide(true, true, true, false, 2, 1));
  }

  @Test void botFollowsTheLastHumanEvenToAChannelWithOnlyThem() {
    assertEquals(JOIN, decide(true, true, true, false, 0, 1));
  }

  @Test void botLeavesWhenTheLastHumanLeavesOrGoesAfk() {
    assertEquals(LEAVE, decide(true, true, false, false, 0, 0));
    assertEquals(LEAVE, decide(true, true, true, true, 0, 1));
  }

  @Test void botStaysWhileHumansRemain() {
    assertEquals(NONE, decide(true, true, false, false, 1, 0));
    assertEquals(NONE, decide(true, true, true, true, 1, 1));
  }

  @Test void channelRegistrationRequiresViewAndSendPermissionsAndPreservesTheCurrentRoute() throws Exception {
    var tmp = Files.createTempDirectory("cwbot");
    var cfg = Config.of(Map.of("AUDIO_DIR", tmp.resolve("audio").toString(), "QUEUE_DB", tmp.resolve("q.db").toString())::get);
    var db = new Db(cfg);
    db.register(1, 10);

    assertTrue(Bot.canRegisterChannel(1, 1, true, true));
    assertFalse(Bot.canRegisterChannel(1, 1, false, true));
    assertFalse(Bot.canRegisterChannel(1, 1, true, false));
    assertFalse(Bot.canRegisterChannel(1, 2, true, true));
    if (Bot.canRegisterChannel(1, 1, true, false)) db.register(1, 11);

    assertEquals(10L, db.channelOf(1));
  }
}

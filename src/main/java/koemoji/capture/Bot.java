package koemoji.capture;

import koemoji.Config;
import koemoji.queue.Db;

import java.util.*;
import java.util.concurrent.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Discord 側の処理。自動参加・移動・退出(jaoafa/ChatWatcher 方式)、/register /unregister /join /leave、ユーザーごとの音声取得を担う。認識結果の投稿は {@link Transcripts} が行う。 */
public final class Bot extends ListenerAdapter {
  private static final Logger log = LoggerFactory.getLogger(Bot.class);

  private static final long IDLE_EVICT_MS = 5 * 60_000;

  private final Config c;
  private final Db db;
  private final Map<Long, Handler> handlers = new ConcurrentHashMap<>();
  private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
  private volatile JDA jda;
  private volatile Transcripts transcripts;
  private volatile boolean draining;
  private volatile boolean drainSucceeded = true;

  public Bot(Config c, Db db) { this.c = c; this.db = db; }

  public boolean connected() { return jda != null && jda.getStatus() == JDA.Status.CONNECTED; }
  public long activePipelines() { return handlers.values().stream().mapToLong(Handler::size).sum(); }
  public long transcriptInflight() { return transcripts == null ? 0 : transcripts.inflightCount(); }
  public boolean drainSucceeded() { return drainSucceeded; }

  public synchronized void beginDrain() {
    draining = true;
    boolean succeeded = true;
    for (Handler handler : handlers.values()) succeeded &= handler.drain();
    if (!succeeded) drainSucceeded = false;
  }

  public synchronized void resumeDrain() {
    handlers.values().forEach(Handler::resume);
    draining = false;
  }

  public void start() throws InterruptedException {
    jda = JDABuilder.createLight(c.token(), GatewayIntent.GUILD_VOICE_STATES)
        .enableCache(CacheFlag.VOICE_STATE)
        .setMemberCachePolicy(MemberCachePolicy.VOICE)
        .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
        .addEventListeners(this).build().awaitReady();
    var guildOnly = net.dv8tion.jda.api.interactions.InteractionContextType.GUILD;
    var admin = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER);
    jda.updateCommands().addCommands(
        Commands.slash("register", "Enable auto-join and post transcripts in a text channel")
            .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Transcript channel (default: this channel)")
                    .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS), engineOption("ASR engine destination"))
            .setContexts(guildOnly).setDefaultPermissions(admin),
        Commands.slash("unregister", "Disable auto-join and leave the voice channel")
            .addOptions(engineOption("ASR engine destination to remove"))
            .setContexts(guildOnly).setDefaultPermissions(admin),
        Commands.slash("join", "Join a voice channel (default: yours)")
            .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Voice channel to join")
                .setChannelTypes(ChannelType.VOICE, ChannelType.STAGE))
            .setContexts(guildOnly).setDefaultPermissions(admin),
        Commands.slash("leave", "Leave the voice channel")
            .setContexts(guildOnly).setDefaultPermissions(admin)
    ).queue();
    // ChatWatcher はサーバーごとに /chatwatcher を登録していた。koemoji はグローバルコマンドだけを定義するので、古いコマンドを消す
    jda.getGuilds().forEach(g -> g.updateCommands().queue(null, err -> log.warn("could not clear guild commands of {}", g.getId(), err)));
    transcripts = new Transcripts(c, db, id -> jda.getChannelById(MessageChannel.class, id));
    sched.scheduleWithFixedDelay(() -> guard(() -> handlers.values().forEach(Handler::tick)), 100, 100, TimeUnit.MILLISECONDS);
    sched.scheduleWithFixedDelay(() -> guard(transcripts::apply), 250, 250, TimeUnit.MILLISECONDS);
    log.info("bot ready as {}", jda.getSelfUser().getName());
  }

  private OptionData engineOption(String description) {
    var option = new OptionData(OptionType.STRING, "engine", description, false);
    c.engines().stream().distinct().forEach(engine -> option.addChoice(engine, engine));
    return option;
  }

  private static void guard(Runnable r) { try { r.run(); } catch (Throwable t) { log.warn("tick failed", t); } }

  @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
    Guild g = e.getGuild();
    if (g == null) return;
    if (draining) { e.reply("The bot is updating; try again shortly.").setEphemeral(true).queue(); return; }
    switch (e.getName()) {
      case "register" -> {
        var opt = e.getOption("channel");
        var selected = opt != null ? opt.getAsChannel() : e.getGuildChannel();
        var bot = g.getSelfMember();
        if (!(selected instanceof GuildMessageChannel channel)
            || !canRegisterChannel(g.getIdLong(), channel.getGuild().getIdLong(),
                bot.hasPermission(channel, Permission.VIEW_CHANNEL),
                bot.hasPermission(channel, Permission.MESSAGE_SEND))) {
          e.reply("The bot must be able to view and send messages in that text channel.").setEphemeral(true).queue();
          return;
        }
        var engine = e.getOption("engine");
        if (engine == null) db.register(g.getIdLong(), channel.getIdLong());
        else db.register(g.getIdLong(), engine.getAsString(), channel.getIdLong());
        String target = engine == null ? "the default destination" : "engine `" + engine.getAsString() + "`";
        e.reply("Registered " + target + " for <#" + channel.getId() + ">; the bot now joins voice channels automatically.").queue();
      }
      case "unregister" -> {
        var engine = e.getOption("engine");
        if (engine == null) db.unregister(g.getIdLong());
        else db.unregister(g.getIdLong(), engine.getAsString());
        boolean active = db.hasRoutes(g.getIdLong());
        if (!active) disconnect(g);
        e.reply(engine == null ? "Unregistered all destinations." : "Unregistered engine `" + engine.getAsString() + "`.").queue();
      }
      case "join" -> {
        if (!db.hasRoutes(g.getIdLong())) { e.reply("Run /register first.").setEphemeral(true).queue(); return; }
        var opt = e.getOption("channel");
        var vs = e.getMember() == null ? null : e.getMember().getVoiceState();
        AudioChannel target = opt != null ? opt.getAsChannel().asAudioChannel() : vs == null ? null : vs.getChannel();
        if (target == null) { e.reply("Join a voice channel or specify one.").setEphemeral(true).queue(); return; }
        try {
          connect(g, target);
          e.reply("Joined " + target.getAsMention()).queue();
        } catch (Exception ex) {
          log.warn("join failed", ex);
          e.reply("Failed to join: " + ex.getMessage()).setEphemeral(true).queue();
        }
      }
      case "leave" -> {
        if (!g.getAudioManager().isConnected()) { e.reply("Not in a voice channel.").setEphemeral(true).queue(); return; }
        disconnect(g);
        e.reply("Left.").queue();
      }
      default -> {}
    }
  }

  static boolean canRegisterChannel(long guildId, long channelGuildId, boolean canView, boolean canSend) {
    return guildId == channelGuildId && canView && canSend;
  }

  private void connect(Guild g, AudioChannel ch) {
    if (!db.hasRoutes(g.getIdLong())) return;
    Handler h = handlers.computeIfAbsent(g.getIdLong(), id -> new Handler(g));
    g.getAudioManager().setReceivingHandler(h);
    g.getAudioManager().openAudioConnection(ch);
  }

  private void disconnect(Guild g) {
    g.getAudioManager().closeAudioConnection();
    g.getAudioManager().setReceivingHandler(null);
    Handler h = handlers.remove(g.getIdLong());
    if (h != null) h.close();
  }

  private static long humans(AudioChannel ch) {
    return ch.getMembers().stream().filter(m -> !m.getUser().isBot()).count();
  }

  private static boolean isAfk(AudioChannel ch) {
    var afk = ch.getGuild().getAfkChannel();
    return afk != null && afk.getIdLong() == ch.getIdLong();
  }

  enum Move { NONE, JOIN, LEAVE }

  /** 人間のボイス状態が変わったときの動作を決める。人数は、変更後の bot のいるチャンネルと参加先チャンネルにいる人間の数。 */
  static Move decide(boolean inChannel, boolean leftBotChannel, boolean joinedAny, boolean joinedAfk, long botChannelHumans, long joinedHumans) {
    boolean joinable = joinedAny && !joinedAfk;
    if (!inChannel) return joinable ? Move.JOIN : Move.NONE;
    if (!leftBotChannel) return Move.NONE;
    if (joinable && joinedHumans > botChannelHumans) return Move.JOIN;
    return botChannelHumans == 0 ? Move.LEAVE : Move.NONE;
  }

  /** どこにも参加していなければ参加し、人間の多いチャンネルへ追従し、最後の人間が抜けたら退出する。 */
  @Override public void onGuildVoiceUpdate(GuildVoiceUpdateEvent e) {
    Guild g = e.getGuild();
    if (e.getMember().getUser().isBot()) {
      if (!draining && e.getMember().equals(g.getSelfMember()) && e.getChannelJoined() == null) disconnect(g);
      return;
    }
    if (draining) return;
    if (!db.hasRoutes(g.getIdLong())) return;
    AudioChannel from = e.getChannelLeft(), to = e.getChannelJoined(), cur = g.getSelfMember().getVoiceState().getChannel();
    switch (decide(cur != null, cur != null && from != null && from.getIdLong() == cur.getIdLong(),
        to != null, to != null && isAfk(to), cur == null ? 0 : humans(cur), to == null ? 0 : humans(to))) {
      case JOIN -> connect(g, to);
      case LEAVE -> disconnect(g);
      case NONE -> {}
    }
  }

  /** JDA の音声を受け取り、ユーザーごとのパイプラインへ振り分ける。 */
  private final class Handler implements AudioReceiveHandler {
    private final Guild guild;
    private final Map<Long, UserPipeline> users = new ConcurrentHashMap<>();
    private final Map<Long, String> usernames = new ConcurrentHashMap<>();
    private boolean accepting = true;

    Handler(Guild g) { this.guild = g; }

    @Override public boolean canReceiveUser() { return true; }

    @Override public synchronized void handleUserAudio(UserAudio ua) {
      if (!accepting) return;
      var user = ua.getUser();
      if (user.isBot() && !c.includeBots()) return;
      long uid = user.getIdLong();
      usernames.computeIfAbsent(uid, k -> user.getName());
      users.computeIfAbsent(uid, k -> UserPipeline.withUtteranceStartTime(c, db, usernames.get(uid),
          (id, startedAtMs) -> transcripts.register(id, guild.getIdLong(), uid, startedAtMs, usernames.get(uid))))
          .accept(ua.getAudioData(1.0));
    }

    void tick() {
      users.values().forEach(p -> {
        try { p.tick(); } catch (Throwable t) { log.warn("tick failed", t); }  // 1 人の失敗で他のユーザーの処理を止めない
      });
      users.values().removeIf(p -> {
        if (!p.idle(IDLE_EVICT_MS)) return false;
        p.close();
        return true;
      });
    }
    synchronized boolean drain() {
      accepting = false;
      boolean succeeded = true;
      for (UserPipeline p : users.values()) {
        try { succeeded &= p.closeForDrain(); }
        catch (Throwable t) { succeeded = false; log.warn("close failed", t); }  // 残りのユーザーも閉じる必要がある
      }
      users.clear();
      return succeeded;
    }
    synchronized void resume() { accepting = true; }
    long size() { return users.size(); }
    void close() { drain(); }
  }
}

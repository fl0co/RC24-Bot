/*
 * MIT License
 *
 * Copyright (c) 2017-2019 RiiConnect24 and its contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
 * (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package xyz.rc24.bot;

import ch.qos.logback.classic.Logger;
import co.aikar.idb.DB;
import co.aikar.idb.DatabaseOptions;
import co.aikar.idb.PooledDatabaseOptions;
import com.jagrosh.jdautilities.command.CommandClientBuilder;
import io.sentry.Sentry;
import javax.security.auth.login.LoginException;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.ShutdownEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import xyz.rc24.bot.commands.botadm.Bash;
import xyz.rc24.bot.commands.botadm.Eval;
import xyz.rc24.bot.commands.botadm.Shutdown;
import xyz.rc24.bot.commands.general.BirthdayCmd;
import xyz.rc24.bot.commands.general.InviteCmd;
import xyz.rc24.bot.commands.general.PingCmd;
import xyz.rc24.bot.commands.general.SetBirthdayCmd;
import xyz.rc24.bot.commands.tools.MailParseListener;
import xyz.rc24.bot.commands.tools.MailPatchCmd;
import xyz.rc24.bot.commands.tools.ServerSettingsCmd;
import xyz.rc24.bot.commands.tools.StatsCmd;
import xyz.rc24.bot.commands.wii.*;
import xyz.rc24.bot.core.BotCore;
import xyz.rc24.bot.core.entities.impl.BotCoreImpl;
import xyz.rc24.bot.database.BirthdayDataManager;
import xyz.rc24.bot.database.CodeDataManager;
import xyz.rc24.bot.database.Database;
import xyz.rc24.bot.database.GuildSettingsDataManager;
import xyz.rc24.bot.events.Morpher;
import xyz.rc24.bot.events.ServerLog;
import xyz.rc24.bot.managers.BirthdayManager;
import xyz.rc24.bot.managers.BlacklistManager;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Add all commands, and start all events.
 *
 * @author Spotlight and Artuto
 */

public class Bot extends ListenerAdapter
{
    public BotCore core;

    public BlacklistManager bManager;
    public Config config;

    private ScheduledExecutorService bdaysScheduler;
    private ScheduledExecutorService musicNightScheduler;

    // Database & Data managers
    private Database db;
    private BirthdayDataManager birthdayDataManager;
    private CodeDataManager codeDataManager;
    private GuildSettingsDataManager guildSettingsDataManager;

    // Managers
    private BirthdayManager birthdayManager;

    private final Logger logger = RiiConnect24Bot.getLogger();

    void run() throws IOException, LoginException
    {
        RiiConnect24Bot.setInstance(this);
        this.config = new Config();
        this.core = new BotCoreImpl(this);

        // Start database
        this.db = initDatabase();
        this.birthdayDataManager = new BirthdayDataManager(db);
        this.codeDataManager = new CodeDataManager(this);
        this.guildSettingsDataManager = new GuildSettingsDataManager(this);

        bManager = new BlacklistManager();
        bdaysScheduler = new ScheduledThreadPoolExecutor(40);
        musicNightScheduler = new ScheduledThreadPoolExecutor(40);

        // Start managers
        this.birthdayManager = new BirthdayManager(getBirthdayDataManager());

        // Start Sentry (if enabled)
        if(config.isSentryEnabled() && !(config.getSentryDSN().isEmpty()))
            Sentry.init(config.getSentryDSN());

        // Register commands
        CommandClientBuilder client = new CommandClientBuilder()
                .setGame(Game.playing(config.getPlaying()))
                .setStatus(config.getStatus())
                .setEmojis(Const.SUCCESS_E, Const.WARN_E, Const.ERROR_E)
                .setLinkedCacheSize(10)
                .setOwnerId(String.valueOf(config.getPrimaryOwner()))
                .setPrefix("@mention")
                .setServerInvite("https://discord.gg/5rw6Tur")
                .setGuildSettingsManager(getGuildSettingsDataManager());

        // Convert List<Long> of secondary owners to String[] so we can set later
        List<Long> owners = config.getSecondaryOwners();
        String[] coOwners = new String[owners.size()];

        for(int i = 0; i < owners.size(); i++)
            coOwners[i] = String.valueOf(owners.get(i));

        // Set all co-owners
        client.setCoOwnerIds(coOwners);

        client.addCommands(
                // Bot administration
                new Bash(), new Eval(this), new Shutdown(),

                // General
                new BirthdayCmd(), new InviteCmd(), new PingCmd(), new SetBirthdayCmd(),

                // Tools
                new ServerSettingsCmd(this), new MailPatchCmd(config), new StatsCmd(),

                // Wii-related
                new AddCmd(this), new CodeCmd(this), new BlocksCmd(), new ErrorInfo(config.isDebug()),
                new DNS(), new Wads(), new WiiWare());

        // JDA Connection
        JDABuilder builder = new JDABuilder(config.getToken())
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .setGame(Game.playing("Loading..."))
                .addEventListener(this, client.build(), new ServerLog(this), new MailParseListener(this))
                .setAudioEnabled(false);

        if(config.isMorpherEnabled())
            builder.addEventListener(new Morpher(config));

        builder.build();
    }

    @Override
    public void onReady(ReadyEvent event)
    {
        logger.info("Done loading!");

        // Check if we need to set a game
        if(config.getPlaying().isEmpty())
            event.getJDA().getPresence().setGame(Game.playing("Type " + config.getPrefix() + "help"));

        ZonedDateTime localNow = OffsetDateTime.now().atZoneSameInstant(ZoneId.of("UTC-6"));
        ZoneId currentZone = ZoneId.of("UTC-6"); // CST FTW
        ZonedDateTime zonedNow = ZonedDateTime.of(localNow.toLocalDateTime(), currentZone);

        // It'll default to Type <prefix>help, per using the default game above.
        if(config.birthdaysAreEnabled())
        {
            // Every day at 8AM
            ZonedDateTime zonedNext8 = zonedNow.withHour(8).withMinute(0).withSecond(0);
            if(zonedNow.compareTo(zonedNext8) > 0)
                zonedNext8 = zonedNext8.plusDays(1);
            Duration duration = Duration.between(zonedNow, zonedNext8);
            long initialDelay = duration.getSeconds();

            bdaysScheduler.scheduleWithFixedDelay(() -> getBirthdayManager().updateBirthdays(event.getJDA(),
                    config.getBirthdayChannel()), initialDelay, 86400, TimeUnit.SECONDS);
        }

        if(config.isMusicNightReminderEnabled())
        {
            ZonedDateTime zonedNext = zonedNow.withHour(19).withMinute(45).withSecond(0);
            if(zonedNow.compareTo(zonedNext) > 0)
                zonedNext = zonedNext.plusDays(1);
            Duration duration = Duration.between(zonedNow, zonedNext);
            long initialDelay = duration.getSeconds();

            musicNightScheduler.scheduleWithFixedDelay(() -> reminderMusicNight(event.getJDA()),
                    initialDelay, 86400, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onShutdown(ShutdownEvent event)
    {
        bdaysScheduler.shutdown();
        musicNightScheduler.shutdown();
        DB.close();
    }

    private Database initDatabase()
    {
        if(config.getDatabaseUser().isEmpty() || config.getDatabasePassword().isEmpty() ||
                config.getDatabase().isEmpty() || config.getDatabaseHost().isEmpty())
            throw new IllegalStateException("You haven't configured database details in the config file!");

        DatabaseOptions options = DatabaseOptions.builder()
                .mysql(config.getDatabaseUser(), config.getDatabasePassword(), config.getDatabase(), config.getDatabaseHost())
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .dataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource")
                .build();

        Map<String, Object> props = new HashMap<String, Object>()
        {{
            put("useSSL", config.useSSL());
            put("verifyServerCertificate", config.verifyServerCertificate());
            put("autoReconnect", config.autoReconnect());
        }};

        co.aikar.idb.Database db = PooledDatabaseOptions.builder()
                .dataSourceProperties(props)
                .options(options)
                .createHikariDatabase();

        DB.setGlobalDatabase(db);

        return new Database();
    }

    private void reminderMusicNight(JDA jda)
    {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("CST"));
        c.setTime(new Date());
        if(! (c.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY))
        {
            // Not today, m8
            return;
        }

        TextChannel general = jda.getTextChannelById(258999527783137280L);
        if(general == null || ! (general.canTalk())) return;

        general.sendMessage("\u23F0 <@98938149316599808> **Music night in 15 minutes!**").queue();
    }

    public BotCore getCore()
    {
        return core;
    }

    public Config getConfig()
    {
        return config;
    }

    public Database getDatabase()
    {
        return db;
    }

    // Data managers
    public BirthdayDataManager getBirthdayDataManager()
    {
        return birthdayDataManager;
    }

    public CodeDataManager getCodeDataManager()
    {
        return codeDataManager;
    }

    public GuildSettingsDataManager getGuildSettingsDataManager()
    {
        return guildSettingsDataManager;
    }

    // Managers
    public BirthdayManager getBirthdayManager()
    {
        return birthdayManager;
    }

    public BlacklistManager getBlacklistManager()
    {
        return bManager;
    }
}

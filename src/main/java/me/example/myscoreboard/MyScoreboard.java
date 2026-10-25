package me.example.myscoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MyScoreboard extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final String[] ENTRIES = {
            ChatColor.BLACK.toString(),
            ChatColor.DARK_BLUE.toString(),
            ChatColor.DARK_GREEN.toString(),
            ChatColor.DARK_AQUA.toString(),
            ChatColor.DARK_PURPLE.toString(),
            ChatColor.GOLD.toString(),
            ChatColor.GRAY.toString(),
            ChatColor.WHITE.toString()
    };

    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("don").setExecutor((sender, command, label, args) -> {
            sender.sendMessage(MINI_MESSAGE.deserialize("<gradient:#ff69b4:#ff1493><bold>WoonTime</bold></gradient> <gray>» <white>Купить донат можно у <light_purple>@WoonTimebot<white>."));
            return true;
        });

        for (Player player : Bukkit.getOnlinePlayers()) {
            createBoard(player);
            updateBoard(player);
            updateTab(player);
        }

        long interval = Math.max(1L,
                getConfig().getLong("update-interval-ticks", 20L));

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!boards.containsKey(player.getUniqueId())) {
                    createBoard(player);
                }
                updateBoard(player);
                updateTab(player);
            }
        }, 1L, interval);

        getLogger().info("MyScoreboard enabled!");
    }

    private Component text(String value) {
        return LEGACY.deserialize(value == null ? "" : value);
    }

    private void updateTab(Player player) {
        Component header = MINI_MESSAGE.deserialize("<gradient:#ff69b4:#ff1493><bold>WoonTime.Su</bold></gradient>\\n<gray>Добро пожаловать на <light_purple>WoonTime</light_purple>!");
        Component footer = MINI_MESSAGE.deserialize("<dark_gray>━━━━━━━━━━━━━━━━━━━━━━━━</dark_gray>\\n<light_purple>Игроков онлайн: <white>" + Bukkit.getOnlinePlayers().size() + "</white>\\n<gray>Купить донат: <light_purple>/don</light_purple>");
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    private void createBoard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

        Component title = MINI_MESSAGE.deserialize(
                "<gradient:#ff69b4:#ff1493><bold>WoonTime.su</bold></gradient>");

        Objective objective = board.registerNewObjective(
                "mysidebar",
                Criteria.DUMMY,
                title
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Hide score numbers on Paper versions that expose the number-format API.
        hideScoreNumbers(objective);

        String[] teamNames = {"diamonds", "rating", "kd", "mc", "separator", "donate", "address"};
        for (int i = 0; i < teamNames.length; i++) {
            Team team = board.registerNewTeam(teamNames[i]);
            team.addEntry(ENTRIES[i]);
            objective.getScore(ENTRIES[i]).setScore(teamNames.length - i);
        }

        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
    }

    private void hideScoreNumbers(Objective objective) {
        try {
            Class<?> formatClass;
            try {
                formatClass = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            } catch (ClassNotFoundException ignored) {
                formatClass = Class.forName("org.bukkit.scoreboard.NumberFormat");
            }
            Object blankFormat = formatClass.getMethod("blank").invoke(null);
            Objective.class.getMethod("numberFormat", formatClass).invoke(objective, blankFormat);
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("Could not hide scoreboard numbers on this server version: " + exception.getMessage());
        }
    }

    private void setLine(Scoreboard board, String teamName,
                         String prefix, String suffix) {
        Team team = board.getTeam(teamName);
        if (team != null) {
            team.prefix(text(prefix));
            team.suffix(text(suffix));
        }
    }

    private void updateBoard(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            createBoard(player);
            board = boards.get(player.getUniqueId());
        }

        int kills = player.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = player.getStatistic(Statistic.DEATHS);
        setLine(board, "kd", "&7КД: &d" + kills + " &7| &c" + deaths, "");

        String rating = "—";
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            rating = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%woontime_rating%");
        }
        setLine(board, "rating", "&7Рейтинг: &d", rating);

        String diamonds = "0";
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            diamonds = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%woontime_emeralds%");
            if (diamonds == null || diamonds.contains("%")) diamonds = "0";
        }
        setLine(board, "diamonds", "&7Алмазы: &b", diamonds);
        setLine(board, "mc", "&7mc", "");
        setLine(board, "separator", "&8----------------", "");
        setLine(board, "donate", "&dКупить донат", " &7@WoonTimebot");

        Team addressTeam = board.getTeam("address");
        if (addressTeam != null) {
            addressTeam.prefix(MINI_MESSAGE.deserialize("<gradient:#ff69b4:#ff1493><bold>WoonTime.su</bold></gradient>"));
            addressTeam.suffix(Component.empty());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            createBoard(player);
            updateBoard(player);
            updateTab(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(player.getUniqueId())) {
                player.setScoreboard(
                        Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
    }
}

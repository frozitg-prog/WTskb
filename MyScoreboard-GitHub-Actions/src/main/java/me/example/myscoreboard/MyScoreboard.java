package me.example.myscoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MyScoreboard extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private static final String[] ENTRIES = {
            ChatColor.BLACK.toString(),
            ChatColor.DARK_BLUE.toString(),
            ChatColor.DARK_GREEN.toString(),
            ChatColor.DARK_AQUA.toString(),
            ChatColor.DARK_RED.toString(),
            ChatColor.DARK_PURPLE.toString(),
            ChatColor.GOLD.toString()
    };

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupEconomy();
        getServer().getPluginManager().registerEvents(this, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            createBoard(player);
            updateBoard(player);
        }

        long interval = Math.max(1L,
                getConfig().getLong("update-interval-ticks", 20L));

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!boards.containsKey(player.getUniqueId())) {
                    createBoard(player);
                }
                updateBoard(player);
            }
        }, 1L, interval);

        getLogger().info("MyScoreboard enabled!");
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found; balance will show unavailable.");
            return;
        }

        RegisteredServiceProvider<Economy> provider =
                getServer().getServicesManager().getRegistration(Economy.class);

        if (provider != null) {
            economy = provider.getProvider();
        }

        if (economy == null) {
            getLogger().warning(
                    "No Vault economy provider found; install a compatible economy plugin.");
        }
    }

    private Component text(String value) {
        return LEGACY.deserialize(value == null ? "" : value);
    }

    private void createBoard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

        Objective objective = board.registerNewObjective(
                "mysidebar",
                Criteria.DUMMY,
                text(getConfig().getString("server-name", "&b&lMY SERVER"))
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        String[] teamNames = {
                "player", "online", "balance", "kills", "deaths", "ping", "address"
        };

        for (int i = 0; i < teamNames.length; i++) {
            Team team = board.registerNewTeam(teamNames[i]);
            team.addEntry(ENTRIES[i]);
            objective.getScore(ENTRIES[i]).setScore(teamNames.length - i);
        }

        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
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

        setLine(board, "player", "&7Ник: ", "&f" + player.getName());

        setLine(board, "online", "&7Онлайн: ",
                "&b" + Bukkit.getOnlinePlayers().size()
                        + "&7/&f" + Bukkit.getMaxPlayers());

        String balance = economy == null
                ? "недоступен"
                : String.format(Locale.US, "%.2f", economy.getBalance(player));

        setLine(board, "balance", "&7Баланс: ",
                economy == null ? "&c" + balance : "&a$" + balance);

        setLine(board, "kills", "&7Убийства: ",
                "&f" + player.getStatistic(Statistic.PLAYER_KILLS));

        setLine(board, "deaths", "&7Смерти: ",
                "&f" + player.getStatistic(Statistic.DEATHS));

        setLine(board, "ping", "&7Пинг: ",
                "&b" + player.getPing() + " ms");

        setLine(board, "address", "",
                getConfig().getString("server-address", "&7play.myserver.net"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            createBoard(player);
            updateBoard(player);
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

package me.crazyliroy.hebrewauth;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

public class HebrewAuth extends JavaPlugin implements Listener {

    private final Set<UUID> loggedInPlayers = new HashSet<>();
    private File usersFile;
    private FileConfiguration usersConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupUsersFile();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("HebrewAuth enabled successfully!");
    }

    private void setupUsersFile() {
        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            try {
                usersFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        usersConfig = YamlConfiguration.loadConfiguration(usersFile);
    }

    private void saveUsersFile() {
        try {
            usersConfig.save(usersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Component msg(String path) {
        String prefix = getConfig().getString("messages.prefix", "");
        String text = getConfig().getString("messages." + path, path);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + text);
    }

    private Component rawMsg(String path) {
        String text = getConfig().getString("messages." + path, path);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loggedInPlayers.remove(player.getUniqueId());
        String key = player.getName().toLowerCase();

        Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(10), Duration.ofMillis(500));

        if (usersConfig.contains("users." + key)) {
            player.sendMessage(msg("join-login-subtitle"));
            player.showTitle(Title.title(rawMsg("join-login-title"), rawMsg("join-login-subtitle"), times));
        } else {
            player.sendMessage(msg("join-register-subtitle"));
            player.showTitle(Title.title(rawMsg("join-register-title"), rawMsg("join-register-subtitle"), times));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loggedInPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!loggedInPlayers.contains(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (!loggedInPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg("must-login"));
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!loggedInPlayers.contains(player.getUniqueId())) {
            String cmd = event.getMessage().toLowerCase();
            if (!cmd.startsWith("/login") && !cmd.startsWith("/register")) {
                event.setCancelled(true);
                player.sendMessage(msg("must-login"));
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("register") && sender instanceof Player player) {
            if (loggedInPlayers.contains(player.getUniqueId())) {
                player.sendMessage(msg("already-logged-in"));
                return true;
            }
            String key = player.getName().toLowerCase();
            if (usersConfig.contains("users." + key)) {
                player.sendMessage(msg("already-registered"));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(msg("register-usage"));
                return true;
            }
            if (!args[0].equals(args[1])) {
                player.sendMessage(msg("password-mismatch"));
                return true;
            }

            setPlayerPassword(key, args[0]);
            loggedInPlayers.add(player.getUniqueId());
            player.clearTitle();
            player.sendMessage(msg("register-success"));
            return true;
        }

        if (cmdName.equals("login") && sender instanceof Player player) {
            if (loggedInPlayers.contains(player.getUniqueId())) {
                player.sendMessage(msg("already-logged-in"));
                return true;
            }
            String key = player.getName().toLowerCase();
            if (!usersConfig.contains("users." + key)) {
                player.sendMessage(msg("not-registered"));
                return true;
            }
            if (args.length != 1) {
                player.sendMessage(msg("login-usage"));
                return true;
            }

            if (checkPassword(key, args[0])) {
                loggedInPlayers.add(player.getUniqueId());
                player.clearTitle();
                player.sendMessage(msg("login-success"));
            } else {
                player.sendMessage(msg("wrong-password"));
            }
            return true;
        }

        if (cmdName.equals("passwordconfig")) {
            if (sender instanceof Player player) {
                if (!loggedInPlayers.contains(player.getUniqueId())) {
                    player.sendMessage(msg("must-login"));
                    return true;
                }
                List<String> managers = getConfig().getStringList("password-managers");
                boolean isAuthorized = managers.stream().anyMatch(m -> m.equalsIgnoreCase(player.getName()));
                if (!isAuthorized) {
                    player.sendMessage(msg("no-permission"));
                    return true;
                }
            }

            if (args.length != 2) {
                sender.sendMessage(msg("admin-usage"));
                return true;
            }

            String targetKey = args[0].toLowerCase();
            String newPassword = args[1];

            if (!usersConfig.contains("users." + targetKey)) {
                sender.sendMessage(msg("target-not-registered"));
                return true;
            }

            setPlayerPassword(targetKey, newPassword);
            String successRaw = getConfig().getString("messages.prefix", "") +
                    getConfig().getString("messages.admin-password-changed", "").replace("%player%", args[0]);
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(successRaw));
            return true;
        }

        return false;
    }

    private void setPlayerPassword(String userKey, String plainPassword) {
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        String hash = hashWithSalt(plainPassword, salt);

        usersConfig.set("users." + userKey + ".salt", salt);
        usersConfig.set("users." + userKey + ".hash", hash);
        saveUsersFile();
    }

    private boolean checkPassword(String userKey, String plainPassword) {
        String salt = usersConfig.getString("users." + userKey + ".salt");
        String storedHash = usersConfig.getString("users." + userKey + ".hash");
        if (salt == null || storedHash == null) return false;
        return storedHash.equals(hashWithSalt(plainPassword, salt));
    }

    private String hashWithSalt(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest((salt + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encoded);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

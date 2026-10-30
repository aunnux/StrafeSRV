package dev.strafesrv.link;

import dev.strafesrv.StrafeSRV;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links Minecraft accounts to Strafe accounts. Flow: the player runs /strafe link and gets a code,
 * then posts "!link CODE" in the link room on Strafe (bots cannot read PMs - they are end-to-end encrypted).
 */
public final class LinkManager {

    public record LinkResult(UUID uuid, String playerName) {}

    private record Pending(UUID uuid, String name, long expiresAt) {}

    private final StrafeSRV plugin;
    private final File file;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, UUID> strafeToUuid = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public LinkManager(StrafeSRV plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "links.yml");
    }

    public synchronized void load() {
        strafeToUuid.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yaml.getConfigurationSection("links");
        if (sec == null) return;
        for (String strafeId : sec.getKeys(false)) {
            try {
                strafeToUuid.put(strafeId, UUID.fromString(sec.getString(strafeId, "")));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring malformed entry in links.yml: " + strafeId);
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, UUID> e : strafeToUuid.entrySet()) yaml.set("links." + e.getKey(), e.getValue().toString());
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save links.yml: " + ex.getMessage());
        }
    }

    /** Creates (or replaces) the player's pending code. */
    public String createCode(Player p) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(x -> x.expiresAt() < now);
        pending.values().removeIf(x -> x.uuid().equals(p.getUniqueId()));
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (pending.containsKey(code));
        long ttl = Math.max(1, plugin.getConfig().getInt("linking.code-expiry-minutes", 10)) * 60_000L;
        pending.put(code, new Pending(p.getUniqueId(), p.getName(), now + ttl));
        return code;
    }

    /** Called when a Strafe user posts "!link CODE". Returns null if the code is unknown or expired. */
    public synchronized LinkResult complete(String code, String strafeUserId) {
        Pending p = pending.remove(code);
        if (p == null || p.expiresAt() < System.currentTimeMillis()) return null;
        strafeToUuid.values().removeIf(u -> u.equals(p.uuid()));
        strafeToUuid.put(strafeUserId, p.uuid());
        save();
        return new LinkResult(p.uuid(), p.name());
    }

    /** Removes the player's link and returns the Strafe user id that was linked, or null. */
    public synchronized String unlink(UUID uuid) {
        for (Map.Entry<String, UUID> e : strafeToUuid.entrySet()) {
            if (e.getValue().equals(uuid)) {
                String id = e.getKey();
                strafeToUuid.remove(id);
                save();
                return id;
            }
        }
        return null;
    }

    public String strafeIdOf(UUID uuid) {
        for (Map.Entry<String, UUID> e : strafeToUuid.entrySet()) {
            if (e.getValue().equals(uuid)) return e.getKey();
        }
        return null;
    }
}

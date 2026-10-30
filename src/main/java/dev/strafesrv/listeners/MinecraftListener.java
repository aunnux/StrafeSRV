package dev.strafesrv.listeners;

import dev.strafesrv.StrafeSRV;
import dev.strafesrv.util.Text;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;

/** Minecraft → Strafe. */
public final class MinecraftListener implements Listener {
    private final StrafeSRV plugin;

    public MinecraftListener(StrafeSRV plugin) {
        this.plugin = plugin;
    }

    private Map<String, String> base(Player p) {
        Map<String, String> v = new HashMap<>();
        v.put("username", plugin.safe(p.getName()));
        v.put("displayname", plugin.safe(ChatColor.stripColor(p.getDisplayName())));
        v.put("uuid", p.getUniqueId().toString());
        return v;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.minecraft-to-strafe", true)) return;
        String msg = ChatColor.stripColor(e.getMessage());
        if (msg == null || msg.isBlank()) return;
        Map<String, String> v = base(e.getPlayer());
        v.put("message", plugin.safe(msg));
        plugin.relay("messages.minecraft-to-strafe.chat", v);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (e.getPlayer().hasPermission("strafesrv.silent")) return;
        Map<String, String> v = base(e.getPlayer());
        v.put("world", plugin.safe(e.getPlayer().getWorld().getName()));
        plugin.relay("messages.minecraft-to-strafe.join", v);
        plugin.refreshPresenceSoon();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (e.getPlayer().hasPermission("strafesrv.silent")) return;
        Map<String, String> v = base(e.getPlayer());
        v.put("world", plugin.safe(e.getPlayer().getWorld().getName()));
        plugin.relay("messages.minecraft-to-strafe.quit", v);
        plugin.refreshPresenceSoon();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onDeath(PlayerDeathEvent e) {
        if (e.getEntity().hasPermission("strafesrv.silent")) return;
        String dm = e.getDeathMessage();
        if (dm == null || dm.isBlank()) return;
        Map<String, String> v = base(e.getEntity());
        v.put("message", plugin.safe(ChatColor.stripColor(dm)));
        plugin.relay("messages.minecraft-to-strafe.death", v);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("strafesrv.silent")) return;
        if (Boolean.FALSE.equals(p.getWorld().getGameRuleValue(GameRule.ANNOUNCE_ADVANCEMENTS))) return;
        String key = e.getAdvancement().getKey().getKey(); // e.g. "story/mine_stone"
        if (key.startsWith("recipes/") || key.endsWith("/root")) return; // invisible bookkeeping advancements
        Map<String, String> v = base(p);
        v.put("advancement", plugin.safe(Text.prettyKey(key)));
        plugin.relay("messages.minecraft-to-strafe.advancement", v);
    }
}

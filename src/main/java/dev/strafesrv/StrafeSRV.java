package dev.strafesrv;

import dev.strafesrv.api.StrafeApi;
import dev.strafesrv.bridge.StrafeBridge;
import dev.strafesrv.commands.StrafeCommand;
import dev.strafesrv.console.ConsoleBridge;
import dev.strafesrv.gateway.GatewayClient;
import dev.strafesrv.link.LinkManager;
import dev.strafesrv.listeners.MinecraftListener;
import dev.strafesrv.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** StrafeSRV: a DiscordSRV-style bridge between a Paper/Spigot server and Strafe.chat. */
public final class StrafeSRV extends JavaPlugin {

    private volatile StrafeApi api;
    private GatewayClient gateway;
    private volatile StrafeBridge bridge;
    private ConsoleBridge console;
    private LinkManager links;
    private BukkitTask presenceTask;
    private boolean startAnnounced;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        links = new LinkManager(this);
        links.load();

        Bukkit.getPluginManager().registerEvents(new MinecraftListener(this), this);
        PluginCommand cmd = getCommand("strafe");
        if (cmd != null) {
            StrafeCommand handler = new StrafeCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        startBridge();
    }

    @Override
    public void onDisable() {
        stopBridge(true);
    }

    // ───────────────────────── lifecycle ─────────────────────────

    private void startBridge() {
        String token = getConfig().getString("strafe.token", "").trim();
        if (token.isEmpty()) {
            getLogger().warning("No bot token set (strafe.token in config.yml). StrafeSRV is idle until you add one and run /strafe reload.");
            return;
        }
        String instance = trimSlash(getConfig().getString("strafe.instance-url", "https://app.strafe.chat"));
        String apiUrl = getConfig().getString("strafe.api-url", "").trim();
        String gatewayUrl = getConfig().getString("strafe.gateway-url", "").trim();
        if (apiUrl.isEmpty()) apiUrl = instance + "/api";
        if (gatewayUrl.isEmpty()) gatewayUrl = instance.replaceFirst("^http", "ws") + "/gateway/events";

        if (chatRoomId().isEmpty()) getLogger().warning("channels.chat is not set - nothing will be bridged until you set a room ID.");

        int maxLen = getConfig().getInt("limits.max-message-length", 2000);
        api = new StrafeApi(apiUrl, token, getDescription().getVersion(), getLogger(), maxLen);
        bridge = new StrafeBridge(this, api);
        gateway = new GatewayClient(gatewayUrl, token, getLogger(), bridge);
        bridge.setGateway(gateway);

        String consoleRoom = consoleRoomId();
        if (!consoleRoom.isEmpty()) {
            if (consoleRoom.equals(chatRoomId())) {
                getLogger().warning("channels.console must differ from channels.chat - console mirroring disabled.");
            } else {
                console = new ConsoleBridge(this, api, consoleRoom, maxLen,
                        getConfig().getInt("console.flush-interval-seconds", 3));
                console.attach();
            }
        }

        presenceTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            StrafeBridge b = bridge;
            if (b != null) b.sendPresence();
        }, 100L, 600L);

        getLogger().info("Connecting to " + gatewayUrl + " ...");
        gateway.start();
    }

    private void stopBridge(boolean announceStop) {
        if (presenceTask != null) {
            presenceTask.cancel();
            presenceTask = null;
        }
        if (console != null) {
            console.detach();
            console = null;
        }
        StrafeApi a = api;
        StrafeBridge b = bridge;
        if (announceStop && a != null && b != null && b.isReady()) {
            CompletableFuture<StrafeApi.Response> f = relay("messages.minecraft-to-strafe.server-stop", Map.of());
            if (f != null) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
        if (gateway != null) {
            gateway.stop();
            gateway = null;
        }
        if (a != null) a.shutdown();
        api = null;
        bridge = null;
    }

    public void reloadBridge() {
        reloadConfig();
        links.load();
        stopBridge(false);
        startBridge();
    }

    /** Called by the bridge on every READY. */
    public void onStrafeReady() {
        if (!startAnnounced) {
            startAnnounced = true;
            relay("messages.minecraft-to-strafe.server-start", Map.of());
        }
    }

    // ───────────────────────── helpers used by other classes ─────────────────────────

    public LinkManager links() {
        return links;
    }

    public String chatRoomId() {
        return getConfig().getString("channels.chat", "").trim();
    }

    public String consoleRoomId() {
        return getConfig().getString("channels.console", "").trim();
    }

    /** The room where "!link CODE" is accepted; falls back to the chat room. */
    public String linkRoomId() {
        String l = getConfig().getString("channels.link", "").trim();
        return l.isEmpty() ? chatRoomId() : l;
    }

    /** Makes player-controlled text safe to post to Strafe (no pings; optional markdown escaping). */
    public String safe(String s) {
        String out = Text.neutralizeMentions(s == null ? "" : s);
        if (getConfig().getBoolean("chat.escape-markdown", false)) out = Text.escapeMarkdown(out);
        return out;
    }

    /** Posts a configured message template to the chat room. Returns null when nothing was sent. */
    public CompletableFuture<StrafeApi.Response> relay(String configPath, Map<String, String> vars) {
        StrafeApi a = api;
        String room = chatRoomId();
        if (a == null || room.isEmpty()) return null;
        String fmt = getConfig().getString(configPath, "");
        if (fmt == null || fmt.isBlank()) return null;
        String text = Text.fill(fmt, vars).trim();
        return text.isEmpty() ? null : a.sendText(room, text);
    }

    public void refreshPresenceSoon() {
        // Join/quit events fire before the player list changes; wait a tick.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            StrafeBridge b = bridge;
            if (b != null) b.sendPresence();
        }, 2L);
    }

    public void revokeLinkedRole(String strafeUserId) {
        StrafeBridge b = bridge;
        if (b != null) b.revokeLinkedRole(strafeUserId);
    }

    public List<String> statusLines() {
        StrafeBridge b = bridge;
        return b == null ? List.of("Not running (no bot token configured).") : b.status();
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}

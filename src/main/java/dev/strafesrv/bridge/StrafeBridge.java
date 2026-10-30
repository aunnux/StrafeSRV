package dev.strafesrv.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.strafesrv.StrafeSRV;
import dev.strafesrv.api.StrafeApi;
import dev.strafesrv.gateway.GatewayClient;
import dev.strafesrv.link.LinkManager;
import dev.strafesrv.util.Json;
import dev.strafesrv.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Routes gateway events: relays Strafe chat into Minecraft, runs console commands, handles !link, keeps caches fresh. */
public final class StrafeBridge implements GatewayClient.Handler {
    private static final Pattern USER = Pattern.compile("<@(\\d+)>");
    private static final Pattern ROLE = Pattern.compile("<@&(\\d+)>");
    private static final Pattern ROOM = Pattern.compile("<#(\\d+)>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");

    private final StrafeSRV plugin;
    private final StrafeApi api;
    private final StrafeState state = new StrafeState();
    private volatile GatewayClient gateway;
    private volatile boolean ready;
    private volatile String lastPresence;

    public StrafeBridge(StrafeSRV plugin, StrafeApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    public void setGateway(GatewayClient gateway) {
        this.gateway = gateway;
    }

    public boolean isReady() {
        return ready;
    }

    // ───────────────────────── GatewayClient.Handler ─────────────────────────

    @Override
    public void onReady(JsonObject d) {
        state.loadReady(d);
        ready = true;
        lastPresence = null; // activities belong to the connection: report again after every READY
        plugin.getLogger().info("Connected to Strafe as " + state.meName + " (" + state.meId + ").");

        Set<String> spaces = new LinkedHashSet<>();
        checkRoom("chat", plugin.chatRoomId(), spaces);
        checkRoom("console", plugin.consoleRoomId(), spaces);
        checkRoom("link", plugin.linkRoomId(), spaces);
        for (String s : spaces) loadMembers(s);

        Bukkit.getScheduler().runTask(plugin, this::sendPresence);
        plugin.onStrafeReady();
    }

    @Override
    public void onGatewayError(int code, String message) {
        plugin.getLogger().warning("Strafe gateway error " + code + ": " + message);
    }

    @Override
    public void onSessionRevoked(String reason) {
        ready = false;
        plugin.getLogger().severe("Strafe session revoked (" + reason + "). Was the bot token reset? Update config.yml and /strafe reload.");
    }

    @Override
    public void onDisconnected() {
        ready = false;
    }

    @Override
    public void onEvent(String type, JsonObject env, JsonObject d) {
        if (type == null) return;
        String envSpace = Json.str(env, "space_id");
        switch (type) {
            case "MESSAGE_CREATE":
                onMessage(d);
                break;
            case "SPACE_MEMBER_ADD":
            case "SPACE_MEMBER_UPDATE":
                state.upsertMember(Json.firstNonNull(Json.str(d, "space_id"), envSpace), d);
                break;
            case "SPACE_MEMBER_REMOVE":
                state.removeMember(Json.firstNonNull(Json.str(d, "space_id"), envSpace), Json.str(d, "user_id"));
                break;
            case "USER_UPDATE":
                state.updateUser(Json.str(d, "user_id"), d);
                break;
            case "SPACE_ROLE_CREATE":
            case "SPACE_ROLE_UPDATE":
                if (envSpace != null) state.putRole(envSpace, d);
                break;
            case "SPACE_ROLE_DELETE":
                if (envSpace != null) state.removeRole(envSpace, Json.str(d, "role_id"));
                break;
            case "SPACE_UPDATE":
                if (envSpace != null && d.has("roles")) state.setRoles(envSpace, Json.arr(d, "roles"));
                break;
            case "SPACE_ROOM_CREATE": {
                String sid = Json.firstNonNull(Json.str(d, "space_id"), envSpace);
                state.putRoom(sid, d);
                String rid = Json.str(d, "id");
                if (rid != null && gateway != null) gateway.subscribe(rid); // rooms created while connected need a manual subscribe
                break;
            }
            case "SPACE_ROOM_UPDATE": {
                String sid = Json.firstNonNull(Json.str(d, "space_id"), envSpace, state.spaceOf(Json.str(d, "id")));
                state.putRoom(sid, d);
                break;
            }
            case "SPACE_ROOM_DELETE":
                state.removeRoom(Json.str(d, "room_id"));
                break;
            case "SPACE_CREATE":
                onSpaceCreate(d);
                break;
            case "SESSION_REVOKED":
                onSessionRevoked(Json.str(d, "reason"));
                break;
            default:
                break;
        }
    }

    // ───────────────────────── setup helpers ─────────────────────────

    private void checkRoom(String label, String roomId, Set<String> spaces) {
        if (roomId == null || roomId.isEmpty()) return;
        StrafeState.RoomInfo r = state.room(roomId);
        if (r == null) {
            plugin.getLogger().warning("The " + label + " room " + roomId + " is not visible to the bot. Check the ID, that the bot is in that space, and that it has View Room + Read Message History + Send Messages there.");
            return;
        }
        if (r.e2ee()) {
            plugin.getLogger().warning("The " + label + " room #" + r.name() + " is end-to-end encrypted. Bots can only work in plain rooms - turn encryption off for it.");
        }
        spaces.add(r.spaceId());
    }

    private void loadMembers(String spaceId) {
        try {
            StrafeApi.Response r = api.request("GET", "/spaces/" + spaceId + "/members", null).get(60, TimeUnit.SECONDS);
            if (!r.ok()) {
                plugin.getLogger().warning("Could not load members of space " + spaceId + ": HTTP " + r.status);
                return;
            }
            JsonElement el = r.element();
            JsonArray arr = null;
            if (el != null && el.isJsonArray()) arr = el.getAsJsonArray();
            else if (el != null && el.isJsonObject()) arr = Json.arr(el.getAsJsonObject(), "members");
            if (arr == null) return;
            for (JsonElement e : arr) if (e.isJsonObject()) state.upsertMember(spaceId, e.getAsJsonObject());
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load members of space " + spaceId + ": " + e);
        }
    }

    /** The bot was added to a space while running: learn its rooms and subscribe (rooms are not in SPACE_CREATE). */
    private void onSpaceCreate(JsonObject d) {
        String sid = Json.str(d, "id");
        GatewayClient g = gateway;
        if (sid == null || g == null) return;
        state.setRoles(sid, Json.arr(d, "roles"));
        g.subscribe(sid);
        try {
            StrafeApi.Response r = api.request("GET", "/spaces/" + sid + "/rooms", null).get(30, TimeUnit.SECONDS);
            JsonElement el = r.ok() ? r.element() : null;
            if (el != null && el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    state.putRoom(sid, e.getAsJsonObject());
                    String rid = Json.str(e.getAsJsonObject(), "id");
                    if (rid != null) g.subscribe(rid);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load rooms of new space " + sid + ": " + e);
        }
        loadMembers(sid);
    }

    // ───────────────────────── incoming messages ─────────────────────────

    private void onMessage(JsonObject d) {
        String roomId = Json.str(d, "room_id");
        String sender = Json.str(d, "sender_id");
        if (roomId == null || sender == null || "0".equals(sender) || sender.equals(state.meId)) return;
        String text = Json.str(d, "plaintext");
        if (text == null) text = ""; // encrypted rooms carry only ciphertext

        String chat = plugin.chatRoomId();
        String console = plugin.consoleRoomId();
        String link = plugin.linkRoomId();

        if (!link.isEmpty() && roomId.equals(link) && handleLinkCommand(d, roomId, sender, text)) return;
        if (!chat.isEmpty() && roomId.equals(chat)) relayToMinecraft(d, roomId, sender, text);
        if (!console.isEmpty() && roomId.equals(console) && !console.equals(chat)) runConsoleCommand(sender, text);
    }

    private void relayToMinecraft(JsonObject d, String roomId, String sender, String text) {
        if (!plugin.getConfig().getBoolean("chat.strafe-to-minecraft", true)) return;
        String spaceId = state.spaceOf(roomId);

        StringBuilder content = new StringBuilder(renderMentions(spaceId, text));
        JsonArray attachments = Json.arr(d, "attachments");
        if (attachments != null) {
            for (JsonElement e : attachments) {
                if (!e.isJsonObject()) continue;
                String fn = Json.str(e.getAsJsonObject(), "filename");
                content.append(content.length() > 0 ? " " : "").append("[file: ").append(fn != null ? fn : "attachment").append(']');
            }
        }
        String msg = cleanForMinecraft(content.toString());
        if (msg.isBlank()) return;
        msg = Text.truncate(msg, Math.max(20, plugin.getConfig().getInt("chat.max-minecraft-message-length", 400)));

        StrafeState.Member m = member(spaceId, sender);
        String name = cleanForMinecraft(m != null ? m.displayName() : sender);
        String role = m != null && spaceId != null ? state.topRoleName(spaceId, m) : null;

        String rolePrefix = "";
        if (role != null) {
            String roleFmt = ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("messages.strafe-to-minecraft.role-format", "[{role}] "));
            rolePrefix = Text.fill(roleFmt, Map.of("role", cleanForMinecraft(role)));
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("name", name);
        vars.put("message", msg);
        vars.put("role", role == null ? "" : cleanForMinecraft(role));
        vars.put("role_prefix", rolePrefix);

        // Colour codes in the config are translated BEFORE user text is inserted, so users cannot inject colours.
        String fmt = ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("messages.strafe-to-minecraft.chat", "&9[Strafe] &f{name} &8» &f{message}"));
        String line = Text.fill(fmt, vars);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(line));
    }

    private static String cleanForMinecraft(String s) {
        return s.replace('\u00A7', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", "")
                .replace("\n", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String renderMentions(String spaceId, String text) {
        text = Text.replace(USER, text, m -> {
            StrafeState.Member mem = member(spaceId, m.group(1));
            return "@" + (mem != null ? mem.displayName() : "unknown");
        });
        text = Text.replace(ROLE, text, m -> {
            String n = spaceId == null ? null : state.roleName(spaceId, m.group(1));
            return "@" + (n != null ? n : "role");
        });
        text = Text.replace(ROOM, text, m -> {
            StrafeState.RoomInfo r = state.room(m.group(1));
            return "#" + (r != null ? r.name() : "room");
        });
        return Text.replace(EMOJI, text, m -> ":" + m.group(1) + ":");
    }

    /** Cached member, or a one-off REST lookup (blocking, but we're on the gateway worker thread). */
    private StrafeState.Member member(String spaceId, String userId) {
        if (spaceId == null || userId == null) return null;
        StrafeState.Member m = state.member(spaceId, userId);
        if (m != null && m.complete) return m;
        try {
            StrafeApi.Response r = api.request("GET", "/spaces/" + spaceId + "/members/" + userId, null).get(15, TimeUnit.SECONDS);
            JsonObject o = r.ok() ? r.object() : null;
            if (o != null) return state.upsertMember(spaceId, o);
        } catch (Exception ignored) {
            // fall through to whatever we had
        }
        return m;
    }

    // ───────────────────────── console commands ─────────────────────────

    private void runConsoleCommand(String sender, String text) {
        List<String> allowed = plugin.getConfig().getStringList("console.allowed-user-ids");
        if (!allowed.contains(sender)) return; // empty list = read-only mirror

        String cmd = text.trim().split("\\R")[0].trim();
        String prefix = plugin.getConfig().getString("console.command-prefix", "");
        if (!prefix.isEmpty()) {
            if (!cmd.startsWith(prefix)) return;
            cmd = cmd.substring(prefix.length()).trim();
        }
        if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
        if (cmd.isEmpty()) return;

        String first = cmd.split("\\s+")[0].toLowerCase(Locale.ROOT);
        for (String blocked : plugin.getConfig().getStringList("console.blocked-commands")) {
            if (first.equals(blocked.toLowerCase(Locale.ROOT))) {
                api.sendMessage(plugin.consoleRoomId(), "\u26D4 `" + first + "` is blocked from Strafe.", null);
                return;
            }
        }
        final String toRun = cmd;
        plugin.getLogger().info("Running console command from Strafe user " + sender + ": " + toRun);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun));
    }

    // ───────────────────────── account linking ─────────────────────────

    private boolean handleLinkCommand(JsonObject d, String roomId, String sender, String text) {
        if (!plugin.getConfig().getBoolean("linking.enabled", true)) return false;
        String prefix = plugin.getConfig().getString("linking.command-prefix", "!");
        String[] parts = text.trim().split("\\s+");
        if (!parts[0].equalsIgnoreCase(prefix + "link")) return false;

        String msgId = Json.str(d, "id");
        if (parts.length < 2) {
            api.sendMessage(roomId, "Usage: `" + prefix + "link <code>` \u2014 run `/strafe link` in Minecraft to get your code.", msgId);
            return true;
        }
        LinkManager.LinkResult res = plugin.links().complete(parts[1], sender);
        if (res == null) {
            api.sendMessage(roomId, "\u274C That code is invalid or has expired. Run `/strafe link` in Minecraft for a new one.", msgId);
            return true;
        }
        api.sendMessage(roomId, "\u2705 <@" + sender + "> is now linked to **" + res.playerName() + "**.", msgId);

        String roleId = plugin.getConfig().getString("linking.linked-role-id", "").trim();
        String spaceId = state.spaceOf(roomId);
        if (!roleId.isEmpty() && spaceId != null) {
            api.grantRole(spaceId, sender, roleId).thenAccept(r -> {
                if (!r.ok()) plugin.getLogger().warning("Could not give the linked role (HTTP " + r.status + "). The bot needs Manage Roles and its role must be ABOVE the linked role. " + r.body);
            });
        }
        UUID uuid = res.uuid();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(ChatColor.GREEN + "Your account is now linked to your Strafe account.");
        });
        return true;
    }

    /** Called from /strafe unlink. */
    public void revokeLinkedRole(String strafeUserId) {
        String roleId = plugin.getConfig().getString("linking.linked-role-id", "").trim();
        String room = plugin.linkRoomId();
        String spaceId = room.isEmpty() ? null : state.spaceOf(room);
        if (roleId.isEmpty() || spaceId == null || strafeUserId == null) return;
        api.revokeRole(spaceId, strafeUserId, roleId);
    }

    // ───────────────────────── presence / status ─────────────────────────

    public void sendPresence() {
        GatewayClient g = gateway;
        if (g == null || !ready || !plugin.getConfig().getBoolean("presence.enabled", true)) return;
        Map<String, String> vars = new HashMap<>();
        vars.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        vars.put("max", String.valueOf(Bukkit.getMaxPlayers()));
        String text = Text.fill(plugin.getConfig().getString("presence.text", "Minecraft"), vars).strip();
        if (text.length() > 128) text = text.substring(0, 128);
        if (text.isEmpty() || text.equals(lastPresence)) return;
        lastPresence = text;
        g.setActivity(text);
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        GatewayClient g = gateway;
        out.add("Gateway: " + (g != null && g.isConnected() ? (ready ? "connected" : "connecting") : "disconnected"));
        out.add("Bot: " + (state.meName != null ? state.meName : "-"));
        out.add(describeRoom("Chat room", plugin.chatRoomId()));
        out.add(describeRoom("Console room", plugin.consoleRoomId()));
        out.add(describeRoom("Link room", plugin.linkRoomId()));
        return out;
    }

    private String describeRoom(String label, String id) {
        if (id.isEmpty()) return label + ": not set";
        StrafeState.RoomInfo r = state.room(id);
        if (r == null) return label + ": " + id + " (NOT VISIBLE to the bot)";
        return label + ": #" + r.name() + (r.e2ee() ? " (ENCRYPTED - bot cannot read it)" : " (ok)");
    }
}

package dev.strafesrv.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.strafesrv.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory picture of what the bot can see (rooms, roles, members), built from READY and kept fresh by events. */
public final class StrafeState {

    public record RoomInfo(String id, String spaceId, String name, boolean e2ee) {}

    public record Role(String id, String name, int position) {}

    public static final class Member {
        public final String userId;
        public volatile String username;
        public volatile String displayName;
        public volatile String nick;
        public volatile boolean bot;
        public volatile boolean complete;
        public volatile List<String> roleIds = List.of();

        Member(String userId) {
            this.userId = userId;
        }

        /** What Strafe shows in a space: space nickname, else global display name, else handle. */
        public String displayName() {
            if (notEmpty(nick)) return nick;
            if (notEmpty(displayName)) return displayName;
            if (notEmpty(username)) return username;
            return userId;
        }

        private static boolean notEmpty(String s) {
            return s != null && !s.isBlank();
        }
    }

    public volatile String meId;
    public volatile String meName;

    private final Map<String, RoomInfo> rooms = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Role>> roles = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Member>> members = new ConcurrentHashMap<>();

    public void clear() {
        rooms.clear();
        roles.clear();
        members.clear();
    }

    // ───────────────────────── READY ─────────────────────────

    public void loadReady(JsonObject d) {
        clear();
        JsonObject user = Json.obj(d, "user");
        if (user != null) {
            meId = Json.str(user, "id");
            meName = Json.str(user, "username");
        }
        JsonArray spaces = Json.arr(d, "spaces");
        if (spaces != null) {
            for (JsonElement e : spaces) {
                if (!e.isJsonObject()) continue;
                JsonObject s = e.getAsJsonObject();
                String sid = Json.str(s, "id");
                if (sid != null) setRoles(sid, Json.arr(s, "roles"));
            }
        }
        JsonObject spaceRooms = Json.obj(d, "space_rooms");
        if (spaceRooms != null) {
            for (Map.Entry<String, JsonElement> en : spaceRooms.entrySet()) {
                if (!en.getValue().isJsonArray()) continue;
                for (JsonElement e : en.getValue().getAsJsonArray()) {
                    if (e.isJsonObject()) putRoom(en.getKey(), e.getAsJsonObject());
                }
            }
        }
    }

    // ───────────────────────── rooms ─────────────────────────

    public void putRoom(String spaceId, JsonObject r) {
        String id = Json.str(r, "id");
        if (id == null) return;
        String name = Json.str(r, "name");
        rooms.put(id, new RoomInfo(id, spaceId, name != null ? name : id, Json.bool(r, "e2ee_enabled", false)));
    }

    public void removeRoom(String roomId) {
        if (roomId != null) rooms.remove(roomId);
    }

    public RoomInfo room(String roomId) {
        return roomId == null ? null : rooms.get(roomId);
    }

    public String spaceOf(String roomId) {
        RoomInfo r = room(roomId);
        return r == null ? null : r.spaceId();
    }

    // ───────────────────────── roles ─────────────────────────

    public void setRoles(String spaceId, JsonArray arr) {
        Map<String, Role> m = new ConcurrentHashMap<>();
        if (arr != null) {
            for (JsonElement e : arr) {
                if (e.isJsonObject()) putRole(m, e.getAsJsonObject());
            }
        }
        roles.put(spaceId, m);
    }

    public void putRole(String spaceId, JsonObject r) {
        putRole(roles.computeIfAbsent(spaceId, k -> new ConcurrentHashMap<>()), r);
    }

    private static void putRole(Map<String, Role> target, JsonObject r) {
        String id = Json.str(r, "id");
        if (id != null) target.put(id, new Role(id, Json.str(r, "name"), Json.integer(r, "position", 0)));
    }

    public void removeRole(String spaceId, String roleId) {
        Map<String, Role> m = roles.get(spaceId);
        if (m != null && roleId != null) m.remove(roleId);
    }

    public String roleName(String spaceId, String roleId) {
        Map<String, Role> m = roles.get(spaceId);
        Role r = m == null ? null : m.get(roleId);
        return r == null ? null : r.name();
    }

    /** Name of the member's highest-ranked role (excluding @everyone, which has position 0). */
    public String topRoleName(String spaceId, Member member) {
        Map<String, Role> m = roles.get(spaceId);
        if (m == null) return null;
        Role best = null;
        for (String id : member.roleIds) {
            Role r = m.get(id);
            if (r != null && r.position() > 0 && r.name() != null && (best == null || r.position() > best.position())) best = r;
        }
        return best == null ? null : best.name();
    }

    // ───────────────────────── members ─────────────────────────

    public Member member(String spaceId, String userId) {
        Map<String, Member> m = members.get(spaceId);
        return m == null ? null : m.get(userId);
    }

    /**
     * Accepts a full member object ({id, username, display_name, nick, roles, bot, ...}) or the
     * SPACE_MEMBER_ADD / SPACE_MEMBER_UPDATE shapes ({user_id|id, role_ids, nick, ...}). Only keys that are
     * present are applied, so partial payloads never wipe known data.
     */
    public Member upsertMember(String spaceId, JsonObject o) {
        if (spaceId == null) return null;
        String uid = Json.firstNonNull(Json.str(o, "id"), Json.str(o, "user_id"));
        if (uid == null) return null;
        Member m = members.computeIfAbsent(spaceId, k -> new ConcurrentHashMap<>()).computeIfAbsent(uid, Member::new);

        if (o.has("username")) {
            m.username = Json.str(o, "username");
            m.complete = true;
        }
        if (o.has("display_name")) m.displayName = Json.str(o, "display_name");
        if (o.has("nick")) m.nick = Json.str(o, "nick");
        if (o.has("bot")) m.bot = Json.bool(o, "bot", false);

        JsonArray ids = o.has("role_ids") ? Json.arr(o, "role_ids") : Json.arr(o, "roles");
        if (ids != null) {
            List<String> list = new ArrayList<>();
            for (JsonElement e : ids) if (e.isJsonPrimitive()) list.add(e.getAsString());
            m.roleIds = list;
        }
        return m;
    }

    public void removeMember(String spaceId, String userId) {
        Map<String, Member> m = members.get(spaceId);
        if (m != null && userId != null) m.remove(userId);
    }

    /** USER_UPDATE: a profile changed; update every space copy of that user. */
    public void updateUser(String userId, JsonObject d) {
        if (userId == null) return;
        for (Map<String, Member> m : members.values()) {
            Member mem = m.get(userId);
            if (mem == null) continue;
            if (d.has("display_name")) mem.displayName = Json.str(d, "display_name");
            if (d.has("username")) mem.username = Json.str(d, "username");
        }
    }
}

package dev.strafesrv.console;

import dev.strafesrv.StrafeSRV;
import dev.strafesrv.api.StrafeApi;
import dev.strafesrv.util.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Mirrors the server console into a Strafe room: a Log4j appender buffers lines and a timer posts
 * them in code blocks. (Commands typed in that room are handled by StrafeBridge.)
 */
public final class ConsoleBridge extends AbstractAppender {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final StrafeSRV plugin;
    private final StrafeApi api;
    private final String roomId;
    private final int maxLen;
    private final int intervalSeconds;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(2000);
    private BukkitTask task;

    public ConsoleBridge(StrafeSRV plugin, StrafeApi api, String roomId, int maxLen, int intervalSeconds) {
        super("StrafeSRV-Console", null, null, true, Property.EMPTY_ARRAY);
        this.plugin = plugin;
        this.api = api;
        this.roomId = roomId;
        this.maxLen = maxLen;
        this.intervalSeconds = Math.max(1, intervalSeconds);
    }

    public void attach() {
        start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(this);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 40L, intervalSeconds * 20L);
    }

    public void detach() {
        if (task != null) task.cancel();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
        flush(); // queue the last lines; StrafeApi.shutdown() lets them finish
    }

    @Override
    public void append(LogEvent event) {
        try {
            // Never forward our own log lines (would loop on errors).
            if (plugin.getName().equals(event.getLoggerName())) return;
            String msg = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
            if (msg == null || msg.isEmpty()) return;
            String line = "[" + TIME.format(LocalTime.now()) + " " + event.getLevel().name() + "] " + Text.stripFormatting(msg);
            queue.offer(line); // full queue = drop (better than growing without bound)
        } catch (Throwable ignored) {
            // an appender must never throw
        }
    }

    private void flush() {
        List<String> lines = new ArrayList<>();
        queue.drainTo(lines, 500);
        if (lines.isEmpty()) return;

        int limit = maxLen - 10; // room for the ``` fences
        StringBuilder cur = new StringBuilder();
        for (String raw : lines) {
            String l = Text.neutralizeMentions(raw.replace("```", "'''"));
            if (l.length() > limit) l = Text.truncate(l, limit);
            if (cur.length() > 0 && cur.length() + l.length() + 1 > limit) {
                post(cur);
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(l);
        }
        if (cur.length() > 0) post(cur);
    }

    private void post(CharSequence body) {
        api.sendMessage(roomId, "```\n" + body + "\n```", null);
    }
}

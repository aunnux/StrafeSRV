package dev.strafesrv.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** String helpers: templating, sanitising, splitting. */
public final class Text {
    private static final Pattern TOKEN = Pattern.compile("\\{(\\w+)}");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern MC_COLOR = Pattern.compile("(?i)\u00A7[0-9A-FK-OR]");
    private static final String ZWSP = "\u200B";

    private Text() {}

    /** Single-pass {placeholder} substitution; unknown placeholders are left as-is. */
    public static String fill(String fmt, Map<String, String> vars) {
        Matcher m = TOKEN.matcher(fmt);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String replace(Pattern p, String in, Function<Matcher, String> fn) {
        Matcher m = p.matcher(in);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Removes ANSI escapes and Minecraft colour codes. */
    public static String stripFormatting(String s) {
        return MC_COLOR.matcher(ANSI.matcher(s).replaceAll("")).replaceAll("");
    }

    /**
     * Stops text typed in Minecraft from pinging people on Strafe: user/role/room/emoji
     * tokens are broken with a zero-width space, as are @everyone / @here.
     */
    public static String neutralizeMentions(String s) {
        return s.replace("<@", "<" + ZWSP + "@")
                .replace("<#", "<" + ZWSP + "#")
                .replace("<:", "<" + ZWSP + ":")
                .replace("<a:", "<" + ZWSP + "a:")
                .replace("@everyone", "@" + ZWSP + "everyone")
                .replace("@here", "@" + ZWSP + "here");
    }

    public static String escapeMarkdown(String s) {
        return s.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }

    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    /** Splits on newlines/spaces where possible so each piece is at most {@code max} chars. */
    public static List<String> chunk(String text, int max) {
        List<String> out = new ArrayList<>();
        String rest = text;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf('\n', max);
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max);
            if (cut < max / 2) cut = max;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** "story/mine_stone" -> "Mine Stone". */
    public static String prettyKey(String key) {
        String last = key.substring(key.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : last.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return sb.toString();
    }
}

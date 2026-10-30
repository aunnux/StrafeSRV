package dev.strafesrv.commands;

import dev.strafesrv.StrafeSRV;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StrafeCommand implements CommandExecutor, TabCompleter {
    private final StrafeSRV plugin;

    public StrafeCommand(StrafeSRV plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                if (!sender.hasPermission("strafesrv.admin")) return noPerm(sender);
                plugin.reloadBridge();
                sender.sendMessage(ChatColor.GREEN + "StrafeSRV reloaded.");
                return true;

            case "status":
                if (!sender.hasPermission("strafesrv.admin")) return noPerm(sender);
                for (String line : plugin.statusLines()) sender.sendMessage(ChatColor.GRAY + line);
                return true;

            case "link": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can link an account.");
                    return true;
                }
                if (!sender.hasPermission("strafesrv.link")) return noPerm(sender);
                if (!plugin.getConfig().getBoolean("linking.enabled", true)) {
                    sender.sendMessage(ChatColor.RED + "Account linking is disabled.");
                    return true;
                }
                if (plugin.links().strafeIdOf(p.getUniqueId()) != null) {
                    sender.sendMessage(ChatColor.YELLOW + "You are already linked. Use /strafe unlink first to link a different account.");
                    return true;
                }
                String code = plugin.links().createCode(p);
                String prefix = plugin.getConfig().getString("linking.command-prefix", "!");
                sender.sendMessage(ChatColor.GREEN + "Post this in the link room on Strafe within "
                        + plugin.getConfig().getInt("linking.code-expiry-minutes", 10) + " minutes:");
                sender.sendMessage(ChatColor.WHITE + prefix + "link " + code);
                return true;
            }

            case "unlink": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can unlink an account.");
                    return true;
                }
                String strafeId = plugin.links().unlink(p.getUniqueId());
                if (strafeId == null) {
                    sender.sendMessage(ChatColor.YELLOW + "You are not linked.");
                } else {
                    plugin.revokeLinkedRole(strafeId);
                    sender.sendMessage(ChatColor.GREEN + "Your account has been unlinked.");
                }
                return true;
            }

            default:
                sender.sendMessage(ChatColor.AQUA + "StrafeSRV: /strafe <link|unlink" + (sender.hasPermission("strafesrv.admin") ? "|reload|status" : "") + ">");
                return true;
        }
    }

    private boolean noPerm(CommandSender s) {
        s.sendMessage(ChatColor.RED + "You don't have permission.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        List<String> all = new ArrayList<>(List.of("link", "unlink"));
        if (sender.hasPermission("strafesrv.admin")) all.addAll(List.of("reload", "status"));
        for (String s : all) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }
}

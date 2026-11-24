package net.starryisle.starryonline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /mistonline [info|reload|reset <player>] */
public final class StarryCommand implements CommandExecutor, TabCompleter {

    private final StarryOnlinePlugin plugin;

    public StarryCommand(StarryOnlinePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("该命令只能玩家使用");
                return true;
            }
            if (!player.hasPermission("mistonline.use")
                    && !player.hasPermission("mistonline.admin")) {
                player.sendMessage("§c没有权限");
                return true;
            }
            plugin.service().showProgress(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                sender.sendMessage("§6MistOnline");
                sender.sendMessage("§7/mistonline §f查看自己的在线奖励进度");
                sender.sendMessage("§7/mistonline reload §f重载配置（管理员）");
                sender.sendMessage("§7/mistonline reset <玩家> §f重置进度（管理员）");
            }
            case "reload" -> {
                if (!canManage(sender)) {
                    sender.sendMessage("§c需要 mistonline.admin 权限或指定管理员");
                    return true;
                }
                plugin.reloadService();
                sender.sendMessage("§aMistOnline 配置已重载");
            }
            case "reset" -> {
                if (!canManage(sender)) {
                    sender.sendMessage("§c需要 mistonline.admin 权限或指定管理员");
                    return true;
                }
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("§c找不到在线玩家: " + args[1]);
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage("§c控制台需要指定玩家: /mistonline reset <玩家>");
                    return true;
                }
                plugin.service().reset(target);
                sender.sendMessage("§a已重置 " + target.getName() + " 的在线奖励进度");
            }
            default -> sender.sendMessage("§c用法: /mistonline [info|help|reload|reset <玩家>]");
        }
        return true;
    }

    private boolean canManage(CommandSender sender) {
        if (!sender.hasPermission("mistonline.admin")) {
            return false;
        }
        String owner = plugin.getConfig().getString("command-owner", "");
        if (owner.isBlank() || sender instanceof org.bukkit.command.ConsoleCommandSender) {
            return true;
        }
        return sender.getName().equalsIgnoreCase(owner);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = canManage(sender)
                    ? List.of("info", "help", "reload", "reset")
                    : List.of("info", "help");
            for (String option : options) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    suggestions.add(option);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset") && canManage(sender)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT)
                        .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    suggestions.add(online.getName());
                }
            }
        }
        return suggestions;
    }
}

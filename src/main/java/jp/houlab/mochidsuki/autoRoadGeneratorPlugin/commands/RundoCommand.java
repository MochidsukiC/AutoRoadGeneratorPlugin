package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.BuildHistoryManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RundoCommand implements CommandExecutor {

    private final AutoRoadGeneratorPluginMain plugin;

    public RundoCommand(AutoRoadGeneratorPluginMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "command.player_only");
            return true;
        }

        Player player = (Player) sender;
        boolean success = BuildHistoryManager.undoLastBuild(player.getUniqueId(), plugin);

        if (success) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "undo.success");
        } else {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "undo.no_history");
        }

        return true;
    }
}

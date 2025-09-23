package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.BuildHistoryManager;
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
            sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            return true;
        }

        Player player = (Player) sender;
        boolean success = BuildHistoryManager.undoLastBuild(player.getUniqueId(), plugin);

        if (success) {
            player.sendMessage(ChatColor.GREEN + "最後に行った設置を取り消しました。");
        } else {
            player.sendMessage(ChatColor.RED + "取り消す操作がありません。");
        }

        return true;
    }
}

package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

/**
 * /roadedit コマンドを処理するクラスです。
 * 編集モードのON/OFFを切り替えます。
 */
public class RoadEditCommand implements CommandExecutor {

    private final AutoRoadGeneratorPluginMain plugin;
    private final RouteVisualizer visualizer;

    public RoadEditCommand(AutoRoadGeneratorPluginMain plugin, RouteVisualizer visualizer) {
        this.plugin = plugin;
        this.visualizer = visualizer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            return true;
        }

        Player player = (Player) sender;
        UUID playerUUID = player.getUniqueId();

        if (args.length == 0 || !args[0].equalsIgnoreCase("edit")) {
            player.sendMessage(ChatColor.RED + "使用法: /roadedit edit");
            return false;
        }

        Set<UUID> editModePlayers = plugin.getEditModePlayers();
        RouteSession session = plugin.getRouteSession(playerUUID);

        // 編集モードのON/OFFをトグル
        if (editModePlayers.contains(playerUUID)) {
            // --- 編集モードを終了 ---
            editModePlayers.remove(playerUUID);

            // 表示をすべて消去
            visualizer.hideAll(player, session);

            // セッション情報をクリア
            session.clearSession();

            player.sendMessage(ChatColor.YELLOW + "編集モードを終了しました。");

        } else {
            // --- 編集モードを開始 ---
            editModePlayers.add(playerUUID);

            // 現在の道路網を可視化
            visualizer.showAll(player, session);

            player.sendMessage(ChatColor.GREEN + "編集モードを開始しました。");
        }

        return true;
    }
}

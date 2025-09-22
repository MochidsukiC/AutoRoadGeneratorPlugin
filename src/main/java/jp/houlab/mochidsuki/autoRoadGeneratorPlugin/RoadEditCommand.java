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
    private final PresetManager presetManager; // Add this line

    public RoadEditCommand(AutoRoadGeneratorPluginMain plugin, RouteVisualizer visualizer, PresetManager presetManager) { // Update constructor
        this.plugin = plugin;
        this.visualizer = visualizer;
        this.presetManager = presetManager; // Initialize presetManager
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            return true;
        }

        Player player = (Player) sender;
        UUID playerUUID = player.getUniqueId();

        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "使用法: /roadedit <edit|build <preset_name>>");
            return false;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("edit")) {
            Set<UUID> editModePlayers = plugin.getEditModePlayers();
            RouteSession session = plugin.getRouteSession(playerUUID);

            // 編集モードのON/OFFをトグル
            if (editModePlayers.contains(playerUUID)) {
                // --- 編集モードを終了 ---
                editModePlayers.remove(playerUUID);

                // 表示をすべて消去
                visualizer.hideAll(player, session);

                // セッション情報をクリア
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    session.clearSession();
                }, 1L);

                player.sendMessage(ChatColor.YELLOW + "編集モードを終了しました。");

            } else {
                // --- 編集モードを開始 ---
                editModePlayers.add(playerUUID);

                // 現在の道路網を可視化
                visualizer.showAll(player, session);

                player.sendMessage(ChatColor.GREEN + "編集モードを開始しました。");
            }
            return true;
        } else if (subCommand.equals("build")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "使用法: /roadedit build <プリセット名>");
                return false;
            }
            String presetName = args[1];

            RouteSession routeSession = plugin.getRouteSession(playerUUID);
            if (routeSession.getCalculatedPath().isEmpty()) {
                player.sendMessage(ChatColor.RED + "先に経路を設定してください。(/roadbrush で経路を設定)");
                return true;
            }

            RoadPreset roadPreset = presetManager.loadPreset(presetName);
            if (roadPreset == null) {
                player.sendMessage(ChatColor.RED + "プリセット \'" + presetName + "\' が見つかりませんでした。");
                return true;
            }

            player.sendMessage(ChatColor.GREEN + "建築計画の計算を開始します... (プリセット: " + presetName + ")");
            new BuildCalculationTask(plugin, playerUUID, routeSession, roadPreset).runTaskAsynchronously(plugin);
            return true;
        } else {
            player.sendMessage(ChatColor.RED + "不明なサブコマンドです。使用法: /roadedit <edit|build <preset_name>>");
            return false;
        }
    }
}

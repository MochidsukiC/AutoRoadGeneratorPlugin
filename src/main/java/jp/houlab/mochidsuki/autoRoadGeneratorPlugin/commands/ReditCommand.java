package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.RouteVisualizer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ReditCommand implements CommandExecutor, TabCompleter {

    private final AutoRoadGeneratorPluginMain plugin;
    private final RouteVisualizer visualizer;

    public ReditCommand(AutoRoadGeneratorPluginMain plugin, RouteVisualizer visualizer) {
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

        if (args.length > 0 && args[0].equalsIgnoreCase("brush")) {
            handleBrush(player);
            return true;
        }

        toggleEditMode(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], Collections.singletonList("brush"), new ArrayList<>());
        }
        return Collections.emptyList();
    }

    private void toggleEditMode(Player player) {
        UUID playerUUID = player.getUniqueId();
        Set<UUID> editModePlayers = plugin.getEditModePlayers();
        RouteSession session = plugin.getRouteSession(playerUUID);

        if (editModePlayers.contains(playerUUID)) {
            editModePlayers.remove(playerUUID);
            visualizer.hideAll(player, session);
            plugin.getServer().getScheduler().runTaskLater(plugin, session::clearSession, 1L);
            player.sendMessage(ChatColor.YELLOW + "編集モードを終了しました。");
        } else {
            editModePlayers.add(playerUUID);
            visualizer.showAll(player, session);
            player.sendMessage(ChatColor.GREEN + "編集モードを開始しました。");
        }
    }

    private void handleBrush(Player player) {
        ItemStack brush = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = brush.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "道路ブラシ (Road Brush)");
            meta.setLore(Collections.singletonList(ChatColor.YELLOW + "クリックした地点をルートに追加します。 (Add a point to the route.)"));
            brush.setItemMeta(meta);
        }

        player.getInventory().addItem(brush);
        player.sendMessage(ChatColor.GREEN + "道路ブラシを入手しました。");
    }
}

package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;

/**
 * /roadbrush コマンドを処理するクラスです。
 */
public class BrushCommand implements CommandExecutor {

    private final AutoRoadGeneratorPluginMain plugin;

    public BrushCommand(AutoRoadGeneratorPluginMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            return true;
        }

        Player player = (Player) sender;

        // 道路ブラシアイテムを作成
        ItemStack brush = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = brush.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "道路ブラシ (Road Brush)");
            meta.setLore(Collections.singletonList(ChatColor.YELLOW + "クリックした地点をルートに追加します。 (Add a point to the route.)"));
            brush.setItemMeta(meta);
        }

        // プレイヤーにブラシを渡す
        player.getInventory().addItem(brush);
        player.sendMessage(ChatColor.GREEN + "道路ブラシを入手しました。");

        return true;
    }
}

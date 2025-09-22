package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public class PresetCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PresetManager presetManager;
    private final Map<UUID, PresetCreationSession> playerSessions;

    public PresetCommand(JavaPlugin plugin, PresetManager presetManager, Map<UUID, PresetCreationSession> playerSessions) {
        this.plugin = plugin;
        this.presetManager = presetManager;
        this.playerSessions = playerSessions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "このコマンドはプレイヤーからのみ実行できます。");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "使用方法: /roadpreset <brush|save <name>|paste <name>>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("brush")) {
            givePresetBrush(player);
            player.sendMessage(ChatColor.GREEN + "プリセットブラシを付与しました。");
            return true;
        } else if (subCommand.equals("save")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "使用方法: /roadpreset save <プリセット名>");
                return true;
            }
            String presetName = args[1];
            savePreset(player, presetName);
            return true;
        } else if (subCommand.equals("paste")) { // Add new paste subcommand
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "使用方法: /roadpreset paste <プリセット名>");
                return true;
            }
            String presetName = args[1];
            pastePreset(player, presetName);
            return true;
        } else {
            player.sendMessage(ChatColor.RED + "不明なサブコマンドです。使用方法: /roadpreset <brush|save <name>|paste <name>>");
            return true;
        }
    }

    private void givePresetBrush(Player player) {
        ItemStack brush = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta = brush.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "プリセットブラシ");
            meta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "左クリック: 始点を設定",
                    ChatColor.YELLOW + "右クリック: 終点を設定",
                    ChatColor.YELLOW + "Shift + クリック: 中心軸の始点/終点を設定"
            ));
            brush.setItemMeta(meta);
        }
        player.getInventory().addItem(brush);
    }

    private void savePreset(Player player, String presetName) {
        PresetCreationSession session = playerSessions.get(player.getUniqueId());

        if (session == null || session.getPos1() == null || session.getPos2() == null || session.getAxisStart() == null || session.getAxisEnd() == null) {
            player.sendMessage(ChatColor.RED + "プリセットを保存するには、すべての座標を設定してください (始点、終点、中心軸の始点、中心軸の終点)。");
            return;
        }

        // Ensure all locations are in the same world
        if (!session.getPos1().getWorld().equals(session.getPos2().getWorld()) ||
            !session.getPos1().getWorld().equals(session.getAxisStart().getWorld()) ||
            !session.getPos1().getWorld().equals(session.getAxisEnd().getWorld())) {
            player.sendMessage(ChatColor.RED + "すべての選択座標は同じワールド内にある必要があります。");
            return;
        }

        Location pos1 = session.getPos1();
        Location pos2 = session.getPos2();
        Location axisStart = session.getAxisStart();
        Location axisEnd = session.getAxisEnd();
        
        // 2. 中心軸の自動推定
        List<Location> rawAxisPath = getLineBetween(axisStart, axisEnd);
        if (rawAxisPath.isEmpty()) {
            player.sendMessage(ChatColor.RED + "中心軸のパスを生成できませんでした。");
            return;
        }

        // 3. 基準点（例: axisStart）を決定します。
        // We'll use axisStart as the reference point for relative coordinates.
        // It's important to use the block coordinates for consistency.
        Location referencePoint = axisStart.getBlock().getLocation();

        // 4. pos1とpos2で定義された範囲内のすべてのブロックをスキャンします。
        Map<Vector, BlockData> blocks = new HashMap<>();
        Location min = getMinLocation(pos1, pos2);
        Location max = getMaxLocation(pos1, pos2);

        Vector dimensions = new Vector(
            max.getBlockX() - min.getBlockX() + 1,
            max.getBlockY() - min.getBlockY() + 1,
            max.getBlockZ() - min.getBlockZ() + 1
        );

        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    Block block = Objects.requireNonNull(min.getWorld()).getBlockAt(x, y, z);
                    Vector relativeVector = block.getLocation().toVector().subtract(referencePoint.toVector());
                    blocks.put(relativeVector, block.getBlockData());
                }
            }
        }

        // 6. 手順2で生成した中心軸パスも、各点を基準点からの相対座標に変換します。
        List<Vector> axisPath = new ArrayList<>();
        for (Location loc : rawAxisPath) {
            axisPath.add(loc.toVector().subtract(referencePoint.toVector()));
        }

        // 7. これらの情報からRoadPresetオブジェクトを生成し、PresetManagerを呼び出してファイルに保存します。
        RoadPreset roadPreset = new RoadPreset(presetName, dimensions, blocks, axisPath);
        presetManager.savePreset(roadPreset);

        // 8. プレイヤーに保存完了を通知し、セッションをクリアします。
        player.sendMessage(ChatColor.GREEN + "プリセット \'" + presetName + "\' を保存しました。");
        playerSessions.remove(player.getUniqueId());
    }

    private void pastePreset(Player player, String presetName) {
        RoadPreset preset = presetManager.loadPreset(presetName);

        if (preset == null) {
            player.sendMessage(ChatColor.RED + "プリセット \'" + presetName + "\' が見つかりませんでした。");
            return;
        }

        // プレイヤーの足元を基準点として貼り付け
        Location pasteReferencePoint = player.getLocation().getBlock().getLocation();

        player.sendMessage(ChatColor.GREEN + "プリセット \'" + presetName + "\' を貼り付け中... (基準点: " + formatLocation(pasteReferencePoint) + ")");

        // プリセット内のブロックをワールドに配置
        for (Map.Entry<Vector, BlockData> entry : preset.getBlocks().entrySet()) {
            Vector relativeVector = entry.getKey();
            BlockData blockData = entry.getValue();

            Location targetLocation = pasteReferencePoint.clone().add(relativeVector);
            // ワールドがnullでないことを確認
            if (targetLocation.getWorld() != null) {
                targetLocation.getBlock().setBlockData(blockData, false); // falseで物理的な更新を抑制
            }
        }

        player.sendMessage(ChatColor.GREEN + "プリセット \'" + presetName + "\' の貼り付けが完了しました。");
    }

    /**
     * Generates a list of block locations forming a straight line between two points (inclusive).
     * Uses a simplified 3D line algorithm (linear interpolation).
     *
     * @param start The starting location.
     * @param end The ending location.
     * @return A list of block locations along the line.
     */
    private List<Location> getLineBetween(Location start, Location end) {
        List<Location> line = new ArrayList<>();

        int x1 = start.getBlockX();
        int y1 = start.getBlockY();
        int z1 = start.getBlockZ();

        int x2 = end.getBlockX();
        int y2 = end.getBlockY();
        int z2 = end.getBlockZ();

        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);

        int N = Math.max(dx, Math.max(dy, dz)); // Number of steps

        if (N == 0) { // Handle case where start and end are the same block
            line.add(new Location(start.getWorld(), x1, y1, z1));
            return line;
        }

        for (int i = 0; i <= N; i++) {
            double t = (double) i / N;
            int currentX = (int) Math.round(x1 + t * (x2 - x1));
            int currentY = (int) Math.round(y1 + t * (y2 - y1));
            int currentZ = (int) Math.round(z1 + t * (z2 - z1));
            line.add(new Location(start.getWorld(), currentX, currentY, currentZ));
        }

        return line;
    }

    private Location getMinLocation(Location loc1, Location loc2) {
        return new Location(
                loc1.getWorld(),
                Math.min(loc1.getBlockX(), loc2.getBlockX()),
                Math.min(loc1.getBlockY(), loc2.getBlockY()),
                Math.min(loc1.getBlockZ(), loc2.getBlockZ())
        );
    }

    private Location getMaxLocation(Location loc1, Location loc2) {
        return new Location(
                loc1.getWorld(),
                Math.max(loc1.getBlockX(), loc2.getBlockX()),
                Math.max(loc1.getBlockY(), loc2.getBlockY()),
                Math.max(loc1.getBlockZ(), loc2.getBlockZ())
        );
    }

    private String formatLocation(Location loc) {
        return "(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }
}

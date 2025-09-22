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
        } else if (subCommand.equals("paste")) {
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
        
        List<Location> rawAxisPath = getLineBetween(axisStart, axisEnd);
        if (rawAxisPath.isEmpty()) {
            player.sendMessage(ChatColor.RED + "中心軸のパスを生成できませんでした。");
            return;
        }

        // ステップ1：プリセット自身のローカル座標系を定義する
        Vector presetForward = axisEnd.toVector().subtract(axisStart.toVector());
        // Y軸の差を無視して水平な向きを基準にする
        presetForward.setY(0);
        if (presetForward.lengthSquared() < 1e-6) {
            presetForward = new Vector(0, 0, 1); // 軸が垂直な場合のフォールバック
        }
        presetForward.normalize();

        Vector presetUp = new Vector(0, 1, 0);
        Vector presetRight = presetUp.clone().crossProduct(presetForward).normalize();

        // 基準点は変わらず axisStart を使用
        Location referencePoint = axisStart.getBlock().getLocation();
        
        Location min = getMinLocation(pos1, pos2);
        Location max = getMaxLocation(pos1, pos2);

        Vector dimensions = new Vector(
            max.getBlockX() - min.getBlockX() + 1,
            max.getBlockY() - min.getBlockY() + 1,
            max.getBlockZ() - min.getBlockZ() + 1
        );

        Map<Vector, BlockData> blocks = new HashMap<>();

        // ステップ2：各ブロックをスキャンし、ローカル座標に変換する
        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    Block block = Objects.requireNonNull(min.getWorld()).getBlockAt(x, y, z);
                    
                    // ワールド座標系での相対ベクトルを計算
                    Vector worldOffset = block.getLocation().toVector().subtract(referencePoint.toVector());

                    // ステップ3：ドット積（内積）を使ってローカル座標 (px, py, pz) を求める
                    double px = worldOffset.dot(presetRight);
                    double py = worldOffset.dot(presetUp);
                    double pz = worldOffset.dot(presetForward);

                    // ブロック座標に丸めて、新しいローカル座標ベクトルを作成
                    Vector localVector = new Vector(Math.round(px), Math.round(py), Math.round(pz));
                    blocks.put(localVector, block.getBlockData());
                }
            }
        }
        
        // 中心軸パスも同様にローカル座標へ変換
        List<Vector> axisPath = new ArrayList<>();
        for (Location loc : rawAxisPath) {
            Vector worldOffset = loc.toVector().subtract(referencePoint.toVector());
            double px = worldOffset.dot(presetRight);
            double py = worldOffset.dot(presetUp);
            double pz = worldOffset.dot(presetForward);
            axisPath.add(new Vector(Math.round(px), Math.round(py), Math.round(pz)));
        }

        RoadPreset roadPreset = new RoadPreset(presetName, dimensions, blocks, axisPath);
        presetManager.savePreset(roadPreset);
        
        player.sendMessage(ChatColor.GREEN + "プリセット '" + presetName + "' を新しい座標系で保存しました。");
        playerSessions.remove(player.getUniqueId());
    }

    private void pastePreset(Player player, String presetName) {
        RoadPreset preset = presetManager.loadPreset(presetName);

        if (preset == null) {
            player.sendMessage(ChatColor.RED + "プリセット '" + presetName + "' が見つかりませんでした。");
            return;
        }

        Location pasteReferencePoint = player.getLocation().getBlock().getLocation();

        player.sendMessage(ChatColor.GREEN + "プリセット '" + presetName + "' を貼り付け中... (基準点: " + formatLocation(pasteReferencePoint) + ")");

        for (Map.Entry<Vector, BlockData> entry : preset.getBlocks().entrySet()) {
            Vector relativeVector = entry.getKey();
            BlockData blockData = entry.getValue();

            Location targetLocation = pasteReferencePoint.clone().add(relativeVector);
            if (targetLocation.getWorld() != null) {
                targetLocation.getBlock().setBlockData(blockData, false);
            }
        }

        player.sendMessage(ChatColor.GREEN + "プリセット '" + presetName + "' の貼り付けが完了しました。");
    }

    private List<Location> getLineBetween(Location start, Location end) {
        List<Location> line = new ArrayList<>();
        int x1 = start.getBlockX(), y1 = start.getBlockY(), z1 = start.getBlockZ();
        int x2 = end.getBlockX(), y2 = end.getBlockY(), z2 = end.getBlockZ();
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, sz = z1 < z2 ? 1 : -1;
        int N = Math.max(dx, Math.max(dy, dz));
        if (N == 0) {
            line.add(start.getBlock().getLocation());
            return line;
        }
        for (int i = 0; i <= N; i++) {
            double t = (double) i / N;
            line.add(new Location(start.getWorld(), x1 + t * (x2 - x1), y1 + t * (y2 - y1), z1 + t * (z2 - z1)).getBlock().getLocation());
        }
        return line;
    }

    private Location getMinLocation(Location loc1, Location loc2) {
        return new Location(loc1.getWorld(), Math.min(loc1.getX(), loc2.getX()), Math.min(loc1.getY(), loc2.getY()), Math.min(loc1.getZ(), loc2.getZ()));
    }

    private Location getMaxLocation(Location loc1, Location loc2) {
        return new Location(loc1.getWorld(), Math.max(loc1.getX(), loc2.getX()), Math.max(loc1.getY(), loc2.getY()), Math.max(loc1.getZ(), loc2.getZ()));
    }

    private String formatLocation(Location loc) {
        return "(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }
}

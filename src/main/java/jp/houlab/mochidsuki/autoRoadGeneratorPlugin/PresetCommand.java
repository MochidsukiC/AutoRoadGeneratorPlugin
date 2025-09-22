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

        // Calculate the rotation angle to align presetForward with the positive X-axis
        // The angle is from the positive X-axis to presetForward
        double rotationAngle = Math.atan2(presetForward.getZ(), presetForward.getX());

        // Calculate cosine and sine for the rotation by -rotationAngle around Y-axis
        double cosRot = Math.cos(-rotationAngle);
        double sinRot = Math.sin(-rotationAngle);

        // 基準点は変わらず axisStart を使用
        Location referencePoint = axisStart.getBlock().getLocation();
        
        Location min = getMinLocation(pos1, pos2);
        Location max = getMaxLocation(pos1, pos2);

        Map<Vector, BlockData> blocks = new HashMap<>();

        // ステップ2：各ブロックをスキャンし、ローカル座標に変換する
        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    Block block = Objects.requireNonNull(min.getWorld()).getBlockAt(x, y, z);
                    
                    // ワールド座標系での相対ベクトルを計算
                    Vector worldOffset = block.getLocation().toVector().subtract(referencePoint.toVector());

                    // Apply rotation to worldOffset to align the original presetForward with the new X-axis
                    double rotatedX = worldOffset.getX() * cosRot - worldOffset.getZ() * sinRot;
                    double rotatedZ = worldOffset.getX() * sinRot + worldOffset.getZ() * cosRot;
                    Vector rotatedWorldOffset = new Vector(rotatedX, worldOffset.getY(), rotatedZ);

                    // ステップ3：ローカル座標 (px, py, pz) を求める
                    // rotatedWorldOffsetの成分がそのまま新しいローカル座標となる
                    double px_saved = rotatedWorldOffset.getX();
                    double py_saved = rotatedWorldOffset.getY();
                    double pz_saved = rotatedWorldOffset.getZ();

                    // ブロック座標に丸めて、新しいローカル座標ベクトルを作成
                    Vector localVector = new Vector(Math.round(px_saved), Math.round(py_saved), Math.round(pz_saved));

                    // BlockDataを回転してから保存（Facingも一緒に回転）
                    BlockData rotatedBlockData = BlockRotationUtil.rotateBlockData(block.getBlockData(), rotationAngle);
                    blocks.put(localVector, rotatedBlockData);
                }
            }
        }
        
        // 中心軸パスも同様にローカル座標へ変換
        List<Vector> axisPath = new ArrayList<>();
        for (Location loc : rawAxisPath) {
            Vector worldOffset = loc.toVector().subtract(referencePoint.toVector());
            
            // Apply rotation to worldOffset
            double rotatedX = worldOffset.getX() * cosRot - worldOffset.getZ() * sinRot;
            double rotatedZ = worldOffset.getX() * sinRot + worldOffset.getZ() * cosRot;
            Vector rotatedWorldOffset = new Vector(rotatedX, worldOffset.getY(), rotatedZ);

            double px_saved = rotatedWorldOffset.getX();
            double py_saved = rotatedWorldOffset.getY();
            double pz_saved = rotatedWorldOffset.getZ();
            axisPath.add(new Vector(Math.round(px_saved), Math.round(py_saved), Math.round(pz_saved)));
        }

        // Convert to slice-based format
        // Step 1: Find bounds in local coordinate system
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (Vector pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getBlockX());
            maxX = Math.max(maxX, pos.getBlockX());
            minY = Math.min(minY, pos.getBlockY());
            maxY = Math.max(maxY, pos.getBlockY());
            minZ = Math.min(minZ, pos.getBlockZ());
            maxZ = Math.max(maxZ, pos.getBlockZ());
        }

        int lengthX = maxX - minX + 1;
        int heightY = maxY - minY + 1;
        int widthZ = maxZ - minZ + 1;

        // Step 2: Find axis origin in local coordinate system
        Vector axisOrigin = axisPath.isEmpty() ? new Vector(0, 0, 0) : axisPath.get(0);

        // Step 3: Calculate axis position within array bounds
        int axisZOffset = axisOrigin.getBlockZ() - minZ;
        int axisYOffset = axisOrigin.getBlockY() - minY;

        // Step 4: Create slices
        List<RoadPreset.PresetSlice> slices = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            RoadPreset.PresetSlice slice = new RoadPreset.PresetSlice(x - minX, widthZ, heightY);

            for (Vector pos : blocks.keySet()) {
                if (pos.getBlockX() == x) {
                    // Calculate relative position from axis origin
                    int relativeZ = pos.getBlockZ() - axisOrigin.getBlockZ();
                    int relativeY = pos.getBlockY() - axisOrigin.getBlockY();

                    // Convert to array indices (axis position within bounds)
                    int arrayZ = relativeZ + axisZOffset;
                    int arrayY = relativeY + axisYOffset;

                    if (arrayZ >= 0 && arrayZ < widthZ && arrayY >= 0 && arrayY < heightY) {
                        slice.setBlock(arrayZ, arrayY, blocks.get(pos));
                    }
                }
            }

            slices.add(slice);
        }

        RoadPreset roadPreset = new RoadPreset(presetName, slices, lengthX, widthZ, heightY, axisZOffset, axisYOffset);
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

        // Implement paste functionality for slice-based presets
        Location axisPoint = pasteReferencePoint; // Use paste point as axis origin

        // Get player's yaw to determine orientation
        float yaw = player.getLocation().getYaw();

        // Calculate direction vectors based on yaw
        double rightX = Math.cos(Math.toRadians(yaw + 90f));
        double rightZ = Math.sin(Math.toRadians(yaw + 90f));
        Vector rightVector = new Vector(rightX, 0, rightZ).normalize();

        double forwardX = Math.cos(Math.toRadians(yaw));
        double forwardZ = Math.sin(Math.toRadians(yaw));
        Vector forwardVector = new Vector(forwardX, 0, forwardZ).normalize();

        Vector upVector = new Vector(0, 1, 0);

        // プレイヤーの向きに基づく回転角度を計算（貼り付け時のFacing回転用）
        double pasteRotationAngle = Math.toRadians(yaw);

        int blocksPlaced = 0;

        // Iterate through slices and place blocks
        for (RoadPreset.PresetSlice slice : preset.getSlices()) {
            int sliceX = slice.getXPosition();

            for (int z = preset.getMinZ(); z <= preset.getMaxZ(); z++) {
                for (int y = preset.getMinY(); y <= preset.getMaxY(); y++) {
                    BlockData blockData = slice.getBlockRelativeToAxis(z, y, preset.getAxisZOffset(), preset.getAxisYOffset());

                    if (blockData != null) {
                        // Calculate world position based on the saved preset's coordinate system
                        // sliceX (preset's X) maps to player's forward (Z)
                        // y (preset's Y) maps to player's up (Y)
                        // z (preset's Z) maps to player's -right (X)
                        Location worldLocation = axisPoint.clone()
                            .add(forwardVector.clone().multiply(sliceX))
                            .add(upVector.clone().multiply(y))
                            .add(rightVector.clone().multiply(-z)); // Note the -z here

                        if (worldLocation.getWorld() != null) {
                            // プレイヤーの向きに合わせてBlockDataを回転（Facingも一緒に回転）
                            BlockData rotatedBlockData = BlockRotationUtil.rotateBlockData(blockData, pasteRotationAngle);
                            worldLocation.getBlock().setBlockData(rotatedBlockData, false);
                            blocksPlaced++;
                        }
                    }
                }
            }
        }

        player.sendMessage(ChatColor.GREEN + "プリセット '" + presetName + "' の貼り付けが完了しました。(" + blocksPlaced + "ブロック配置)");
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
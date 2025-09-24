package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.WallCalculationTask;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.*;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.BlockRotationUtil;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

public class WallPresetCommand implements CommandExecutor, TabCompleter {
    private final AutoRoadGeneratorPluginMain plugin;
    private final WallPresetManager wallPresetManager;
    private final Map<UUID, WallCreationSession> wallSessions;

    public WallPresetCommand(AutoRoadGeneratorPluginMain plugin, WallPresetManager wallPresetManager, Map<UUID, WallCreationSession> wallSessions) {
        this.plugin = plugin;
        this.wallPresetManager = wallPresetManager;
        this.wallSessions = wallSessions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみが実行できます。");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "brush":
                handleBrush(player);
                break;
            case "save":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "使用法: /rwall save <名前>");
                    return true;
                }
                handleSave(player, args[1]);
                break;
            case "paste":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "使用法: /rwall paste <名前>");
                    return true;
                }
                handlePaste(player, args[1]);
                break;
            case "build":
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "使用法: /rwall build <プリセット名> <xオフセット> [yオフセット] [-onlyair] [--noupdateblockdata]");
                    return true;
                }
                try {
                    double xOffset = Double.parseDouble(args[2]);
                    double yOffset = 0.0; // デフォルトのyオフセット
                    boolean onlyAir = false;
                    boolean updateBlockData = true;

                    int optionStartIndex = 3;

                    // 3番目の引数が数値の場合、yオフセットとして処理
                    if (args.length > 3) {
                        try {
                            yOffset = Double.parseDouble(args[3]);
                            optionStartIndex = 4;
                        } catch (NumberFormatException e) {
                            // 3番目の引数が数値でない場合、yオフセットは0でオプションとして処理
                            yOffset = 0.0;
                            optionStartIndex = 3;
                        }
                    }

                    // オプションの解析
                    for (int i = optionStartIndex; i < args.length; i++) {
                        if (args[i].equalsIgnoreCase("-onlyair")) {
                            onlyAir = true;
                        } else if (args[i].equalsIgnoreCase("--noupdateblockdata")) {
                            updateBlockData = false;
                        }
                    }

                    handleBuild(player, args[1], xOffset, yOffset, onlyAir, updateBlockData);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "オフセット値は数値で入力してください。");
                }
                break;
            default:
                sendHelp(player);
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], Arrays.asList("brush", "save", "paste", "build"), new ArrayList<>());
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("paste") || args[0].equalsIgnoreCase("build")) {
                return StringUtil.copyPartialMatches(args[1], wallPresetManager.getPresetNames(), new ArrayList<>());
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("build")) {
                return StringUtil.copyPartialMatches(args[3], Arrays.asList("-onlyair", "--noupdateblockdata"), new ArrayList<>());
            }
        } else if (args.length == 5) {
            if (args[0].equalsIgnoreCase("build")) {
                return StringUtil.copyPartialMatches(args[4], Arrays.asList("-onlyair", "--noupdateblockdata"), new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    private void handleBrush(Player player) {
        ItemStack wallBrush = new ItemStack(Material.STONE_AXE);
        ItemMeta wallMeta = wallBrush.getItemMeta();
        if (wallMeta != null) {
            wallMeta.setDisplayName(ChatColor.GRAY + "塀ブラシ");
            wallMeta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "左クリック: 3D領域の始点を設定",
                    ChatColor.YELLOW + "右クリック: 3D領域の終点を設定",
                    ChatColor.YELLOW + "Shift + 左クリック: 中心軸の始点を設定",
                    ChatColor.YELLOW + "Shift + 右クリック: 中心軸の終点を設定"
            ));
            wallBrush.setItemMeta(wallMeta);
        }
        player.getInventory().addItem(wallBrush);
        player.sendMessage(ChatColor.GREEN + "塀プリセット作成用のブラシを入手しました。");
    }

    private void handleSave(Player player, String presetName) {
        WallCreationSession session = wallSessions.get(player.getUniqueId());

        // デバッグ情報を追加
        if (session == null) {
            player.sendMessage(ChatColor.RED + "セッションが見つかりません。まず塀ブラシを使用して座標を設定してください。");
            return;
        }

        // 各座標の設定状況をチェック
        String debugInfo = ChatColor.GRAY + "[デバッグ] ";
        debugInfo += "始点:" + (session.getPos1() != null ? "設定済み" : "未設定") + " ";
        debugInfo += "終点:" + (session.getPos2() != null ? "設定済み" : "未設定") + " ";
        debugInfo += "軸始点:" + (session.getAxisStart() != null ? "設定済み" : "未設定") + " ";
        debugInfo += "軸終点:" + (session.getAxisEnd() != null ? "設定済み" : "未設定");
        player.sendMessage(debugInfo);

        if (session.getPos1() == null || session.getPos2() == null ||
            session.getAxisStart() == null || session.getAxisEnd() == null) {
            player.sendMessage(ChatColor.RED + "塀プリセットを保存するには、すべての座標を設定してください (3D領域の始点、終点、中心軸の始点、中心軸の終点)。");
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

        Vector presetForward = axisEnd.toVector().subtract(axisStart.toVector());
        presetForward.setY(0);
        if (presetForward.lengthSquared() < 1e-6) {
            presetForward = new Vector(0, 0, 1); // 軸が垂直な場合のフォールバック
        }
        presetForward.normalize();

        double rotationAngle = Math.atan2(presetForward.getZ(), presetForward.getX());

        double cosRot = Math.cos(-rotationAngle);
        double sinRot = Math.sin(-rotationAngle);

        Location referencePoint = axisStart.getBlock().getLocation();

        Location min = getMinLocation(pos1, pos2);
        Location max = getMaxLocation(pos1, pos2);

        Map<Vector, BlockData> blocks = new HashMap<>();

        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    Block block = Objects.requireNonNull(min.getWorld()).getBlockAt(x, y, z);

                    Vector worldOffset = block.getLocation().toVector().subtract(referencePoint.toVector());

                    double rotatedX = worldOffset.getX() * cosRot - worldOffset.getZ() * sinRot;
                    double rotatedZ = worldOffset.getX() * sinRot + worldOffset.getZ() * cosRot;
                    Vector rotatedWorldOffset = new Vector(rotatedX, worldOffset.getY(), rotatedZ);

                    double px_saved = rotatedWorldOffset.getX();
                    double py_saved = rotatedWorldOffset.getY();
                    double pz_saved = rotatedWorldOffset.getZ();

                    Vector localVector = new Vector(Math.round(px_saved), Math.round(py_saved), Math.round(pz_saved));

                    BlockData rotatedBlockData = BlockRotationUtil.rotateBlockData(block.getBlockData(), rotationAngle);
                    blocks.put(localVector, rotatedBlockData);
                }
            }
        }

        List<Vector> axisPath = new ArrayList<>();
        for (Location loc : rawAxisPath) {
            Vector worldOffset = loc.toVector().subtract(referencePoint.toVector());

            double rotatedX = worldOffset.getX() * cosRot - worldOffset.getZ() * sinRot;
            double rotatedZ = worldOffset.getX() * sinRot + worldOffset.getZ() * cosRot;
            Vector rotatedWorldOffset = new Vector(rotatedX, worldOffset.getY(), rotatedZ);

            double px_saved = rotatedWorldOffset.getX();
            double py_saved = rotatedWorldOffset.getY();
            double pz_saved = rotatedWorldOffset.getZ();
            axisPath.add(new Vector(Math.round(px_saved), Math.round(py_saved), Math.round(pz_saved)));
        }

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

        Vector axisOrigin = axisPath.isEmpty() ? new Vector(0, 0, 0) : axisPath.get(0);

        int axisXOffset = axisOrigin.getBlockX() - minX;
        int axisZOffset = axisOrigin.getBlockZ() - minZ;
        int axisYOffset = axisOrigin.getBlockY() - minY;

        List<WallPreset.WallSlice> slices = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            WallPreset.WallSlice slice = new WallPreset.WallSlice(x - minX, widthZ, heightY);

            for (Vector pos : blocks.keySet()) {
                if (pos.getBlockX() == x) {
                    int relativeZ = pos.getBlockZ() - axisOrigin.getBlockZ();
                    int relativeY = pos.getBlockY() - axisOrigin.getBlockY();

                    int arrayZ = relativeZ + axisZOffset;
                    int arrayY = relativeY + axisYOffset;

                    if (arrayZ >= 0 && arrayZ < widthZ && arrayY >= 0 && arrayY < heightY) {
                        slice.setBlock(arrayZ, arrayY, blocks.get(pos));
                        slice.setBlockString(arrayZ, arrayY, blocks.get(pos).getAsString());
                    }
                }
            }

            slices.add(slice);
        }

        WallPreset wallPreset = new WallPreset(presetName, slices, lengthX, widthZ, heightY, axisXOffset, axisZOffset, axisYOffset);
        wallPresetManager.savePreset(wallPreset);

        player.sendMessage(ChatColor.GREEN + "塀プリセット '" + presetName + "' を3D座標系で保存しました。");
        wallSessions.remove(player.getUniqueId());
    }

    private void handlePaste(Player player, String presetName) {
        WallPreset preset = wallPresetManager.loadPreset(presetName);

        if (preset == null) {
            player.sendMessage(ChatColor.RED + "塀プリセット '" + presetName + "' が見つかりませんでした。");
            return;
        }

        Location pasteLocation = player.getLocation().getBlock().getLocation();
        player.sendMessage(ChatColor.GREEN + "塀プリセット '" + presetName + "' を貼り付け中...");

        // Get player's facing direction
        float yaw = player.getLocation().getYaw();

        // Calculate right vector (perpendicular to facing direction)
        double rightX = Math.cos(Math.toRadians(yaw + 90f));
        double rightZ = Math.sin(Math.toRadians(yaw + 90f));
        Vector rightVector = new Vector(rightX, 0, rightZ).normalize();

        Vector upVector = new Vector(0, 1, 0);
        double pasteRotationAngle = Math.toRadians(yaw);

        int blocksPlaced = 0;

        // Calculate forward vector for 3D placement
        double forwardX = Math.cos(Math.toRadians(yaw));
        double forwardZ = Math.sin(Math.toRadians(yaw));
        Vector forwardVector = new Vector(forwardX, 0, forwardZ).normalize();

        for (WallPreset.WallSlice slice : preset.getSlices()) {
            int sliceX = slice.getXPosition();

            for (int z = preset.getMinZ(); z <= preset.getMaxZ(); z++) {
                for (int y = preset.getMinY(); y <= preset.getMaxY(); y++) {
                    BlockData blockData = slice.getBlockRelativeToAxis(z, y, preset.getAxisZOffset(), preset.getAxisYOffset());

                    if (blockData != null) {
                        // Calculate relative X position from axis
                        int relativeX = sliceX - preset.getAxisXOffset();

                        // Place block in 3D space with correct X positioning
                        Location worldLocation = pasteLocation.clone()
                            .add(forwardVector.clone().multiply(relativeX))
                            .add(upVector.clone().multiply(y))
                            .add(rightVector.clone().multiply(z));

                        if (worldLocation.getWorld() != null) {
                            BlockData rotatedBlockData = BlockRotationUtil.rotateBlockData(blockData, pasteRotationAngle);
                            worldLocation.getBlock().setBlockData(rotatedBlockData, true); // Update block connections
                            blocksPlaced++;
                        }
                    }
                }
            }
        }

        player.sendMessage(ChatColor.GREEN + "塀プリセット '" + presetName + "' の貼り付けが完了しました。(" + blocksPlaced + "ブロック配置)");
    }

    private void handleBuild(Player player, String presetName, double xOffset, double yOffset, boolean onlyAir, boolean updateBlockData) {
        UUID playerUUID = player.getUniqueId();
        RouteSession routeSession = plugin.getRouteSession(playerUUID);

        if (routeSession.getCalculatedPath() == null || routeSession.getCalculatedPath().isEmpty()) {
            player.sendMessage(ChatColor.RED + "先に経路を設定してください。(/redit brush で経路を設定)");
            return;
        }

        WallPreset wallPreset = wallPresetManager.loadPreset(presetName);
        if (wallPreset == null) {
            player.sendMessage(ChatColor.RED + "塀プリセット '" + presetName + "' が見つかりませんでした。");
            return;
        }

        String modeMessage = onlyAir ? " (空気ブロックのみ設置モード)" : "";
        String updateMessage = updateBlockData ? "" : " (ブロック更新なし)";
        String xOffsetText = xOffset > 0 ? "右側" : (xOffset < 0 ? "左側" : "中央");
        String yOffsetText = yOffset != 0 ? ", Y=" + yOffset : "";
        player.sendMessage(ChatColor.GREEN + "塀建築計算を開始します... (プリセット: " + presetName +
                          ", X=" + xOffset + " " + xOffsetText + yOffsetText + ")" + modeMessage + updateMessage);

        new WallCalculationTask(plugin, playerUUID, routeSession, wallPreset, xOffset, yOffset, onlyAir, updateBlockData).runTaskAsynchronously(plugin);
    }

    // 既存のhandleBuildメソッドとの互換性を保持
    private void handleBuild(Player player, String presetName, double offset, boolean onlyAir) {
        handleBuild(player, presetName, offset, 0.0, onlyAir, true); // デフォルトでY=0, ブロック更新有効
    }

    // updateBlockDataパラメータ付きの互換性メソッド
    private void handleBuild(Player player, String presetName, double offset, boolean onlyAir, boolean updateBlockData) {
        handleBuild(player, presetName, offset, 0.0, onlyAir, updateBlockData); // デフォルトでY=0
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.AQUA + "--- 塀コマンド ---");
        player.sendMessage(ChatColor.YELLOW + "/rwall brush" + ChatColor.WHITE + " - 塀プリセット作成用のブラシを取得します。");
        player.sendMessage(ChatColor.YELLOW + "/rwall save <名前>" + ChatColor.WHITE + " - 選択範囲を塀プリセットとして保存します。");
        player.sendMessage(ChatColor.YELLOW + "/rwall paste <名前>" + ChatColor.WHITE + " - 足元に塀プリセットを直接設置します。");
        player.sendMessage(ChatColor.YELLOW + "/rwall build <プリセット名> <xオフセット> [yオフセット] [-onlyair] [--noupdateblockdata]" + ChatColor.WHITE + " - 道路経路に沿って3D塀を建設します。正の値で右側、負の値で左側。yオフセットで高さ調整可能。");
    }

    private Location getMinLocation(Location loc1, Location loc2) {
        return new Location(loc1.getWorld(), Math.min(loc1.getX(), loc2.getX()), Math.min(loc1.getY(), loc2.getY()), Math.min(loc1.getZ(), loc2.getZ()));
    }

    private Location getMaxLocation(Location loc1, Location loc2) {
        return new Location(loc1.getWorld(), Math.max(loc1.getX(), loc2.getX()), Math.max(loc1.getY(), loc2.getY()), Math.max(loc1.getZ(), loc2.getZ()));
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
}
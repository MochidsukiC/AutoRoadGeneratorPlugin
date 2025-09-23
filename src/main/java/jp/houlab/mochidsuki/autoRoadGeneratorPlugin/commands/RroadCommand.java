package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.*;
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

public class RroadCommand implements CommandExecutor, TabCompleter {
    private final AutoRoadGeneratorPluginMain plugin;
    private final PresetManager presetManager;
    private final Map<UUID, PresetCreationSession> playerSessions;

    public RroadCommand(AutoRoadGeneratorPluginMain plugin, PresetManager presetManager, Map<UUID, PresetCreationSession> playerSessions) {
        this.plugin = plugin;
        this.presetManager = presetManager;
        this.playerSessions = playerSessions;
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
                    player.sendMessage(ChatColor.RED + "使用法: /rroad save <名前>");
                    return true;
                }
                handleSave(player, args[1]);
                break;
            case "paste":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "使用法: /rroad paste <名前>");
                    return true;
                }
                handlePaste(player, args[1]);
                break;
            case "build":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "使用法: /rroad build <プリセット名>");
                    return true;
                }
                handleBuild(player, args[1]);
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
            return StringUtil.copyPartialMatches(args[0], Arrays.asList("brush", "save", "build", "paste"), new ArrayList<>());
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("build") || args[0].equalsIgnoreCase("paste")) {
                return StringUtil.copyPartialMatches(args[1], presetManager.getPresetNames(), new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    private void handleBuild(Player player, String presetName) {
        UUID playerUUID = player.getUniqueId();
        RouteSession routeSession = plugin.getRouteSession(playerUUID);
        if (routeSession.getCalculatedPath().isEmpty()) {
            player.sendMessage(ChatColor.RED + "先に経路を設定してください。(/redit brush で経路を設定)");
            return;
        }

        RoadPreset roadPreset = presetManager.loadPreset(presetName);
        if (roadPreset == null) {
            player.sendMessage(ChatColor.RED + "プリセット '" + presetName + "' が見つかりませんでした。");
            return;
        }

        player.sendMessage(ChatColor.GREEN + "建築計画の計算を開始します... (プリセット: " + presetName + ")");
        new BuildCalculationTask(plugin, playerUUID, routeSession, roadPreset).runTaskAsynchronously(plugin);
    }

    private void handleBrush(Player player) {
        ItemStack presetBrush = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta presetMeta = presetBrush.getItemMeta();
        if (presetMeta != null) {
            presetMeta.setDisplayName(ChatColor.GOLD + "プリセットブラシ");
            presetMeta.setLore(Arrays.asList(
                    ChatColor.YELLOW + "左クリック: 始点を設定",
                    ChatColor.YELLOW + "右クリック: 終点を設定",
                    ChatColor.YELLOW + "Shift + クリック: 中心軸の始点/終点を設定"
            ));
            presetBrush.setItemMeta(presetMeta);
        }
        player.getInventory().addItem(presetBrush);
        player.sendMessage(ChatColor.GREEN + "プリセット作成用のブラシを入手しました。");
    }

    private void handleSave(Player player, String presetName) {
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

        int axisZOffset = axisOrigin.getBlockZ() - minZ;
        int axisYOffset = axisOrigin.getBlockY() - minY;

        List<RoadPreset.PresetSlice> slices = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            RoadPreset.PresetSlice slice = new RoadPreset.PresetSlice(x - minX, widthZ, heightY);

            for (Vector pos : blocks.keySet()) {
                if (pos.getBlockX() == x) {
                    int relativeZ = pos.getBlockZ() - axisOrigin.getBlockZ();
                    int relativeY = pos.getBlockY() - axisOrigin.getBlockY();

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

    private void handlePaste(Player player, String presetName) {
        RoadPreset preset = presetManager.loadPreset(presetName);

        if (preset == null) {
            player.sendMessage(ChatColor.RED + "プリセット '" + presetName + "' が見つかりませんでした。");
            return;
        }

        Location pasteReferencePoint = player.getLocation().getBlock().getLocation();

        player.sendMessage(ChatColor.GREEN + "プリセット '" + presetName + "' を貼り付け中... (基準点: " + formatLocation(pasteReferencePoint) + ")");

        Location axisPoint = pasteReferencePoint;

        float yaw = player.getLocation().getYaw();

        double rightX = Math.cos(Math.toRadians(yaw + 90f));
        double rightZ = Math.sin(Math.toRadians(yaw + 90f));
        Vector rightVector = new Vector(rightX, 0, rightZ).normalize();

        double forwardX = Math.cos(Math.toRadians(yaw));
        double forwardZ = Math.sin(Math.toRadians(yaw));
        Vector forwardVector = new Vector(forwardX, 0, forwardZ).normalize();

        Vector upVector = new Vector(0, 1, 0);

        double pasteRotationAngle = Math.toRadians(yaw);

        int blocksPlaced = 0;

        for (RoadPreset.PresetSlice slice : preset.getSlices()) {
            int sliceX = slice.getXPosition();

            for (int z = preset.getMinZ(); z <= preset.getMaxZ(); z++) {
                for (int y = preset.getMinY(); y <= preset.getMaxY(); y++) {
                    BlockData blockData = slice.getBlockRelativeToAxis(z, y, preset.getAxisZOffset(), preset.getAxisYOffset());

                    if (blockData != null) {
                        Location worldLocation = axisPoint.clone()
                            .add(forwardVector.clone().multiply(sliceX))
                            .add(upVector.clone().multiply(y))
                            .add(rightVector.clone().multiply(-z)); // Note the -z here

                        if (worldLocation.getWorld() != null) {
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

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.AQUA + "--- 道路コマンド ---");
        player.sendMessage(ChatColor.YELLOW + "/rroad brush" + ChatColor.WHITE + " - 道路プリセット作成用のブラシを取得します。");
        player.sendMessage(ChatColor.YELLOW + "/rroad save <名前>" + ChatColor.WHITE + " - 選択範囲を道路プリセットとして保存します。");
        player.sendMessage(ChatColor.YELLOW + "/rroad build <プリセット名>" + ChatColor.WHITE + " - 経路に沿ってプリセットから道路を建設します。");
        player.sendMessage(ChatColor.YELLOW + "/rroad paste <プリセット名>" + ChatColor.WHITE + " - 足元に道路プリセットを直接設置します。");
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

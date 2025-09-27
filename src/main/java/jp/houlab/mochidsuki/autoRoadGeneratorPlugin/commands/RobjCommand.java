package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.BlockRotationUtil;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.BlockPlacementInfo;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.BuildHistoryManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.BuildPlacementTask;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects.ObjectCreationSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects.ObjectPreset;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects.ObjectPresetManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.StringBlockRotationUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.StringUtil;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RobjCommand implements CommandExecutor, TabCompleter {

    // 定数定義
    /** デフォルトのオブジェクト配置間隔（ブロック） */
    private static final double DEFAULT_PLACEMENT_INTERVAL = 10.0;
    /** 90度回転の基準角度 */
    private static final double QUARTER_TURN_DEGREES = 90.0;
    /** 完全回転の角度（360度） */
    private static final int FULL_ROTATION_DEGREES = 360;
    /** 方向ベクトルの最小長さ閾値 */
    private static final double MIN_DIRECTION_LENGTH = 0.001;
    /** 位置変化の最小距離閾値 */
    private static final double MIN_POSITION_CHANGE = 0.0001;

    private final AutoRoadGeneratorPluginMain plugin;
    private final Map<UUID, ObjectCreationSession> creationSessions;
    private final ObjectPresetManager objectPresetManager;

    public RobjCommand(AutoRoadGeneratorPluginMain plugin, Map<UUID, ObjectCreationSession> creationSessions, ObjectPresetManager objectPresetManager) {
        this.plugin = plugin;
        this.creationSessions = creationSessions;
        this.objectPresetManager = objectPresetManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, sender, "command.player_only");
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
                handleGetBrush(player);
                break;
            case "save":
                if (args.length < 2) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.usage_save");
                    return true;
                }
                handleCreate(player, args[1]);
                break;
            case "place":
                handlePlace(player, args);
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
            return StringUtil.copyPartialMatches(args[0], Arrays.asList("brush", "save", "place"), new ArrayList<>());
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("place")) {
                return StringUtil.copyPartialMatches(args[1], objectPresetManager.getPresetNames(), new ArrayList<>());
            }
        } else if (args.length > 2 && args[0].equalsIgnoreCase("place")) {
            if (args[args.length - 1].startsWith("--")) {
                return StringUtil.copyPartialMatches(args[args.length - 1], Arrays.asList("--interval", "--offset", "--rotate", "--flip", "--noupdateblockdata"), new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    private void sendHelp(Player player) {
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.help_title");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.help_brush");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.help_save");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.help_place_long");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.help_place_options");
    }

    private void handleGetBrush(Player player) {
        ItemStack brush = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = brush.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getMessageManager().getMessage(player, "object.brush_name"));
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(new NamespacedKey(plugin, "brush_type"), PersistentDataType.STRING, "object_creation_brush");
            brush.setItemMeta(meta);
        }
        player.getInventory().addItem(brush);
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.brush_received");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.brush_usage");
    }

    private void handleCreate(Player player, String presetName) {
        ObjectCreationSession session = creationSessions.get(player.getUniqueId());

        if (session == null || !session.isReady()) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.brush_not_ready");
            return;
        }

        Location start = session.getStartLocation();
        Location end = session.getEndLocation();
        Location origin = session.getOriginLocation();
        World world = start.getWorld();

        float yaw = player.getLocation().getYaw();
        int initialYaw = (int) (Math.round(yaw / QUARTER_TURN_DEGREES) * QUARTER_TURN_DEGREES) % FULL_ROTATION_DEGREES;
        if (initialYaw < 0) initialYaw += FULL_ROTATION_DEGREES;

        int minX = Math.min(start.getBlockX(), end.getBlockX());
        int minY = Math.min(start.getBlockY(), end.getBlockY());
        int minZ = Math.min(start.getBlockZ(), end.getBlockZ());
        int maxX = Math.max(start.getBlockX(), end.getBlockX());
        int maxY = Math.max(start.getBlockY(), end.getBlockY());
        int maxZ = Math.max(start.getBlockZ(), end.getBlockZ());

        Map<Vector, BlockData> blocks = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location blockLocation = new Location(world, x, y, z);
                    BlockData blockData = blockLocation.getBlock().getBlockData();
                    if (blockData.getMaterial() != Material.AIR) {
                        Vector worldRelativePos = blockLocation.toVector().subtract(origin.toVector());
                        Vector canonicalRelativePos = worldRelativePos.clone().rotateAroundY(Math.toRadians(-initialYaw));
                        blocks.put(canonicalRelativePos, blockData.clone());
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.selection_empty");
            return;
        }

        Vector dimensions = new Vector(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);

        ObjectPreset preset = new ObjectPreset(presetName, blocks, 0, dimensions);
        objectPresetManager.savePreset(preset);

        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.preset_saved", presetName);
        creationSessions.remove(player.getUniqueId());
    }

    private void handlePlace(Player player, String[] args) {
        if (args.length < 2) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.usage_place");
            return;
        }

        String presetName = args[1];
        double interval = DEFAULT_PLACEMENT_INTERVAL;
        Vector offset = new Vector(0, 0, 0);
        float rotation = 0f;
        String flipAxis = "";
        boolean updateBlockData = true;

        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i].toLowerCase()) {
                    case "--interval":
                        interval = Double.parseDouble(args[++i]);
                        break;
                    case "--offset":
                        String[] coords = args[++i].split(",");
                        offset = new Vector(Double.parseDouble(coords[0]), Double.parseDouble(coords[1]), Double.parseDouble(coords[2]));
                        break;
                    case "--rotate":
                        rotation = Float.parseFloat(args[++i]);
                        break;
                    case "--flip":
                        flipAxis = args[++i].toLowerCase();
                        if (!flipAxis.equals("x") && !flipAxis.equals("z")) throw new IllegalArgumentException("Invalid flip axis");
                        break;
                    case "--noupdateblockdata":
                        updateBlockData = false;
                        break;
                }
            }
        } catch (Exception e) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.invalid_arguments");
            return;
        }

        RouteSession routeSession = plugin.getRouteSession(player.getUniqueId());
        List<Location> path = routeSession.getCalculatedPath();
        if (path == null || path.isEmpty()) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.path_not_found");
            return;
        }

        ObjectPreset preset = objectPresetManager.loadPreset(presetName);
        if (preset == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.preset_not_found", presetName);
            return;
        }

        List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
        List<BlockPlacementInfo> originalBlocks = new ArrayList<>();
        double distanceSinceLast = interval > 0 ? interval : 0;

        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                distanceSinceLast += path.get(i).distance(path.get(i - 1));
            }

            if (distanceSinceLast >= interval) {
                Location pathPoint = path.get(i);

                // Calculate direction vector like in road/wall systems
                Vector direction = calculateDirectionVector(path, i);
                double pathYaw = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));

                Vector tangent = new Vector(Math.cos(Math.toRadians(pathYaw)), 0, Math.sin(Math.toRadians(pathYaw)));
                Vector up = new Vector(0, 1, 0);
                Vector normal = new Vector(Math.cos(Math.toRadians(pathYaw + QUARTER_TURN_DEGREES)), 0, Math.sin(Math.toRadians(pathYaw + QUARTER_TURN_DEGREES)));

                double totalYaw = pathYaw + rotation;

                for (Map.Entry<Vector, BlockData> entry : preset.getBlocks().entrySet()) {
                    Vector canonicalPos = entry.getKey().clone();

                    if (flipAxis.equals("x")) canonicalPos.setX(canonicalPos.getX() * -1);
                    if (flipAxis.equals("z")) canonicalPos.setZ(canonicalPos.getZ() * -1);
                    canonicalPos.rotateAroundY(Math.toRadians(rotation));
                    Vector finalLocalPos = canonicalPos.clone().add(offset);

                    Vector worldDisplacement = new Vector();
                    worldDisplacement.add(tangent.clone().multiply(finalLocalPos.getX()));
                    worldDisplacement.add(up.clone().multiply(finalLocalPos.getY()));
                    worldDisplacement.add(normal.clone().multiply(finalLocalPos.getZ()));

                    Location blockLocation = pathPoint.clone().add(worldDisplacement);

                    // Rotate block data by total rotation (path direction + user rotation)
                    BlockData rotatedBlockData = BlockRotationUtil.rotateBlockData(entry.getValue().clone(), Math.toRadians(totalYaw));

                    originalBlocks.add(new BlockPlacementInfo(blockLocation, blockLocation.getBlock().getBlockData()));
                    worldBlocks.add(new BlockPlacementInfo(blockLocation, rotatedBlockData));
                }
                distanceSinceLast = 0;
            }
        }

        BuildHistoryManager.addBuildHistory(player.getUniqueId(), originalBlocks);
        Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(worldBlocks);
        new BuildPlacementTask(plugin, player.getUniqueId(), placementQueue, false, updateBlockData).runTaskTimer(plugin, 1, 1);

        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "object.placing_objects");
    }

    private String formatLocation(Player player, Location loc) {
        return plugin.getMessageManager().getMessage(player, "location.format", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private Vector calculateDirectionVector(List<Location> path, int index) {
        if (path.size() < 2) return new Vector(1, 0, 0);
        Vector direction;
        if (index == 0) {
            direction = path.get(1).toVector().subtract(path.get(0).toVector());
        } else if (index == path.size() - 1) {
            direction = path.get(index).toVector().subtract(path.get(index - 1).toVector());
        } else {
            Vector incoming = path.get(index).toVector().subtract(path.get(index - 1).toVector());
            Vector outgoing = path.get(index + 1).toVector().subtract(path.get(index).toVector());
            if (incoming.length() > MIN_DIRECTION_LENGTH) incoming.normalize();
            if (outgoing.length() > MIN_DIRECTION_LENGTH) outgoing.normalize();
            direction = incoming.add(outgoing).multiply(0.5);
        }
        if (direction.length() < MIN_DIRECTION_LENGTH) {
            if (index > 0 && path.get(index).distanceSquared(path.get(index - 1)) > MIN_POSITION_CHANGE) {
                return path.get(index).toVector().subtract(path.get(index - 1).toVector()).normalize();
            } else if (path.size() > index + 1 && path.get(index + 1).distanceSquared(path.get(index)) > MIN_POSITION_CHANGE) {
                return path.get(index + 1).toVector().subtract(path.get(index).toVector()).normalize();
            } else {
                return new Vector(1, 0, 0);
            }
        }
        return direction.normalize();
    }
}

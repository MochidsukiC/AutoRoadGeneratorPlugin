package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.BuildCalculationTask;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.PresetCreationSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.PresetManager;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.RoadPreset;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteEdge;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.BlockRotationUtil;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
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
import java.util.Objects;
import java.util.UUID;

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
                handleBrush(player);
                break;
            case "save":
                if (args.length < 2) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.usage_save");
                    return true;
                }
                handleSave(player, args[1]);
                break;
            case "paste":
                if (args.length < 2) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.usage_paste");
                    return true;
                }
                handlePaste(player, args[1]);
                break;
            case "build":
                if (args.length < 2) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.usage_build");
                    return true;
                }
                boolean onlyAir = false;
                boolean updateBlockData = true;

                // オプションの解析
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equalsIgnoreCase("-onlyair")) {
                        onlyAir = true;
                    } else if (args[i].equalsIgnoreCase("--noupdateblockdata")) {
                        updateBlockData = false;
                    }
                }

                handleBuild(player, args[1], onlyAir, updateBlockData);
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
        } else if (args.length > 2) {
            if (args[0].equalsIgnoreCase("build")) {
                List<String> options = new ArrayList<>(Arrays.asList("-onlyair", "--noupdateblockdata"));
                // Prevent suggesting already used options
                for (int i = 2; i < args.length; i++) {
                    options.remove(args[i]);
                }
                return StringUtil.copyPartialMatches(args[args.length - 1], options, new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }

    private void handleBuild(Player player, String presetName, boolean onlyAir, boolean updateBlockData) {
        UUID playerUUID = player.getUniqueId();
        RouteSession routeSession = plugin.getRouteSession(playerUUID);
        List<RouteEdge> edges = routeSession.getEdges();

        if (edges.isEmpty()) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.route_not_set");
            return;
        }

        RoadPreset roadPreset = presetManager.loadPreset(presetName);
        if (roadPreset == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.preset_not_found", presetName);
            return;
        }

        String modeMessage = onlyAir ? " " + plugin.getMessageManager().getMessage(player, "road.build.mode.only_air") : "";
        String updateMessage = !updateBlockData ? " " + plugin.getMessageManager().getMessage(player, "road.build.mode.no_block_update") : "";
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.building_started_details", presetName, edges.size(), modeMessage, updateMessage);


        UUID buildId = UUID.randomUUID();
        BuildCalculationTask.BuildManager.startBuildSession(buildId, edges.size());

        for (RouteEdge edge : edges) {
            // Create a temporary session for each edge to pass its specific path
            RouteSession singleEdgeSession = new RouteSession();
            if (edge.getCalculatedPath() != null && !edge.getCalculatedPath().isEmpty()) {
                singleEdgeSession.setCalculatedPath(edge.getCalculatedPath());
            } else {
                // It's possible an edge exists but its path hasn't been calculated yet.
                // Though the listener should handle this, we can add a fallback or skip.
                plugin.getLogger().warning("Skipping edge " + edge.toString() + " as its path is not calculated.");
                continue; // Or calculate it here if needed
            }

            UUID edgeId = UUID.randomUUID();
            new BuildCalculationTask(plugin, playerUUID, singleEdgeSession, roadPreset, onlyAir, updateBlockData, buildId, edgeId).runTaskAsynchronously(plugin);
        }
    }


    private void handleBrush(Player player) {
        ItemStack presetBrush = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta presetMeta = presetBrush.getItemMeta();
        if (presetMeta != null) {
            presetMeta.setDisplayName(plugin.getMessageManager().getMessage(player, "road.brush_name"));
            presetMeta.setLore(Arrays.asList(
                    plugin.getMessageManager().getMessage(player, "road.brush_lore1"),
                    plugin.getMessageManager().getMessage(player, "road.brush_lore2"),
                    plugin.getMessageManager().getMessage(player, "road.brush_lore3")
            ));
            PersistentDataContainer data = presetMeta.getPersistentDataContainer();
            data.set(new NamespacedKey(plugin, "brush_type"), PersistentDataType.STRING, "road_preset_brush");
            presetBrush.setItemMeta(presetMeta);
        }
        player.getInventory().addItem(presetBrush);
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.brush_received");
    }

    private void handleSave(Player player, String presetName) {
        PresetCreationSession session = playerSessions.get(player.getUniqueId());

        if (session == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "preset.session_not_found");
            return;
        }

        if (session.getPos1() == null || session.getPos2() == null || session.getAxisStart() == null || session.getAxisEnd() == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "preset.coordinates_incomplete");
            return;
        }

        if (!session.getPos1().getWorld().equals(session.getPos2().getWorld()) ||
            !session.getPos1().getWorld().equals(session.getAxisStart().getWorld()) ||
            !session.getPos1().getWorld().equals(session.getAxisEnd().getWorld())) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "preset.different_worlds");
            return;
        }

        Location pos1 = session.getPos1();
        Location pos2 = session.getPos2();
        Location axisStart = session.getAxisStart();
        Location axisEnd = session.getAxisEnd();
        
        List<Location> rawAxisPath = getLineBetween(axisStart, axisEnd);
        if (rawAxisPath.isEmpty()) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "preset.path_generation_failed");
            return;
        }

        Vector presetForward = axisEnd.toVector().subtract(axisStart.toVector());
        presetForward.setY(0);
        if (presetForward.lengthSquared() < 1e-6) {
            presetForward = new Vector(0, 0, 1); // Fallback for vertical axis
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
                        BlockData blockData = blocks.get(pos);
                        slice.setBlock(arrayZ, arrayY, blockData);
                        slice.setBlockString(arrayZ, arrayY, blockData.getAsString());  // Important: Save String as well
                    }
                }
            }

            slices.add(slice);
        }

        RoadPreset roadPreset = new RoadPreset(presetName, slices, lengthX, widthZ, heightY, axisZOffset, axisYOffset);
        presetManager.savePreset(roadPreset);
        
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.preset_saved_new_coord", presetName);
        playerSessions.remove(player.getUniqueId());
    }

    private void handlePaste(Player player, String presetName) {
        RoadPreset preset = presetManager.loadPreset(presetName);

        if (preset == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.preset_not_found", presetName);
            return;
        }

        Location pasteReferencePoint = player.getLocation().getBlock().getLocation();

        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.preset_pasting_with_ref", presetName, formatLocation(player, pasteReferencePoint));

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

        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.preset_paste_complete", presetName, blocksPlaced);
    }

    private void sendHelp(Player player) {
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.help_title");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.help_brush");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.help_save");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.help_build_long");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.help_paste");
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

    private String formatLocation(Player player, Location loc) {
        return plugin.getMessageManager().getMessage(player, "location.format", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}

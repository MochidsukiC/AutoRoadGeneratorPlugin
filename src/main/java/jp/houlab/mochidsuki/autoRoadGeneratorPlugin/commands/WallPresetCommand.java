package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.commands;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build.WallCalculationTask;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.*;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteEdge;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.BlockRotationUtil;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
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
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.usage_save");
                    return true;
                }
                handleSave(player, args[1]);
                break;
            case "paste":
                if (args.length < 2) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.usage_paste");
                    return true;
                }
                handlePaste(player, args[1]);
                break;
            case "build":
                if (args.length < 3) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.usage_build");
                    return true;
                }
                try {
                    double xOffset = Double.parseDouble(args[2]);
                    double yOffset = 0.0; // Default y-offset
                    boolean onlyAir = false;
                    boolean updateBlockData = true;

                    int optionStartIndex = 3;

                    if (args.length > 3) {
                        try {
                            yOffset = Double.parseDouble(args[3]);
                            optionStartIndex = 4;
                        } catch (NumberFormatException e) {
                            yOffset = 0.0;
                            optionStartIndex = 3;
                        }
                    }

                    for (int i = optionStartIndex; i < args.length; i++) {
                        if (args[i].equalsIgnoreCase("-onlyair")) {
                            onlyAir = true;
                        } else if (args[i].equalsIgnoreCase("--noupdateblockdata")) {
                            updateBlockData = false;
                        }
                    }

                    handleBuild(player, args[1], xOffset, yOffset, onlyAir, updateBlockData);
                } catch (NumberFormatException e) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.offset_invalid");
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
            wallMeta.setDisplayName(plugin.getMessageManager().getMessage("wall.brush_name"));
            wallMeta.setLore(Arrays.asList(
                    plugin.getMessageManager().getMessage("wall.brush_lore1"),
                    plugin.getMessageManager().getMessage("wall.brush_lore2"),
                    plugin.getMessageManager().getMessage("wall.brush_lore3"),
                    plugin.getMessageManager().getMessage("wall.brush_lore4")
            ));
            wallBrush.setItemMeta(wallMeta);
        }
        player.getInventory().addItem(wallBrush);
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.brush_received");
    }

    private void handleSave(Player player, String presetName) {
        WallCreationSession session = wallSessions.get(player.getUniqueId());

        if (session == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.session_not_found");
            return;
        }

        if (session.getPos1() == null || session.getPos2() == null ||
            session.getAxisStart() == null || session.getAxisEnd() == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.coordinates_incomplete");
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

        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.preset_saved", presetName);
        wallSessions.remove(player.getUniqueId());
    }

    private void handlePaste(Player player, String presetName) {
        WallPreset preset = wallPresetManager.loadPreset(presetName);

        if (preset == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.preset_not_found", presetName);
            return;
        }

        Location pasteLocation = player.getLocation().getBlock().getLocation();
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.preset_pasting", presetName);

        float yaw = player.getLocation().getYaw();

        double rightX = Math.cos(Math.toRadians(yaw + 90f));
        double rightZ = Math.sin(Math.toRadians(yaw + 90f));
        Vector rightVector = new Vector(rightX, 0, rightZ).normalize();

        Vector upVector = new Vector(0, 1, 0);
        double pasteRotationAngle = Math.toRadians(yaw);

        int blocksPlaced = 0;

        double forwardX = Math.cos(Math.toRadians(yaw));
        double forwardZ = Math.sin(Math.toRadians(yaw));
        Vector forwardVector = new Vector(forwardX, 0, forwardZ).normalize();

        for (WallPreset.WallSlice slice : preset.getSlices()) {
            int sliceX = slice.getXPosition();

            for (int z = preset.getMinZ(); z <= preset.getMaxZ(); z++) {
                for (int y = preset.getMinY(); y <= preset.getMaxY(); y++) {
                    BlockData blockData = slice.getBlockRelativeToAxis(z, y, preset.getAxisZOffset(), preset.getAxisYOffset());

                    if (blockData != null) {
                        int relativeX = sliceX - preset.getAxisXOffset();

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

        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.preset_paste_complete", presetName, blocksPlaced);
    }

    private void handleBuild(Player player, String presetName, double xOffset, double yOffset, boolean onlyAir, boolean updateBlockData) {
        UUID playerUUID = player.getUniqueId();
        RouteSession routeSession = plugin.getRouteSession(playerUUID);
        List<RouteEdge> edges = routeSession.getEdges();

        if (edges.isEmpty()) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "road.route_not_set");
            return;
        }

        WallPreset wallPreset = wallPresetManager.loadPreset(presetName);
        if (wallPreset == null) {
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.preset_not_found", presetName);
            return;
        }

        String modeMessage = onlyAir ? plugin.getMessageManager().getMessage("wall.build_mode_air") : "";
        String updateMessage = updateBlockData ? "" : plugin.getMessageManager().getMessage("wall.build_mode_no_update");
        String xOffsetText = xOffset > 0 ? plugin.getMessageManager().getMessage("wall.build_offset_right") : (xOffset < 0 ? plugin.getMessageManager().getMessage("wall.build_offset_left") : plugin.getMessageManager().getMessage("wall.build_offset_center"));
        String yOffsetText = yOffset != 0 ? ", Y=" + yOffset : "";
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.building_started_details", presetName, xOffset, xOffsetText, yOffsetText, modeMessage, updateMessage);

        UUID buildId = UUID.randomUUID();
        WallCalculationTask.BuildManager.startBuildSession(buildId, edges.size());

        for (RouteEdge edge : edges) {
            RouteSession singleEdgeSession = new RouteSession();
            if (edge.getCalculatedPath() != null && !edge.getCalculatedPath().isEmpty()) {
                singleEdgeSession.setCalculatedPath(edge.getCalculatedPath());
            } else {
                plugin.getLogger().warning("Skipping edge for wall construction as its path is not calculated: " + edge.toString());
                continue;
            }

            UUID edgeId = UUID.randomUUID();
            new WallCalculationTask(plugin, playerUUID, singleEdgeSession, wallPreset, xOffset, yOffset, onlyAir, updateBlockData, buildId, edgeId).runTaskAsynchronously(plugin);
        }
    }

    private void sendHelp(Player player) {
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.help_title");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.help_brush");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.help_save");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.help_paste");
        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "wall.help_build_long");
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

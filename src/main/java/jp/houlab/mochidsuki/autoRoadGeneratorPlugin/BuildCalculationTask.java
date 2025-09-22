package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class BuildCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final RoadPreset roadPreset;

    private record LocalBasis(Vector forward, Vector up, Vector right) {}

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.roadPreset = roadPreset;
    }

    @Override
    public void run() {
        List<Location> path = routeSession.getCalculatedPath();
        if (path == null || path.isEmpty()) {
            return;
        }

        List<LocalBasis> pathBases = calculatePathBases(path);
        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();
        Vector presetOrigin = roadPreset.getAxisPath().isEmpty() ? new Vector(0, 0, 0) : roadPreset.getAxisPath().get(0);

        // 経路上の各点に、プリセット全体を3Dのままスタンプする
        for (int i = 0; i < path.size(); i++) {
            Location pathPoint = path.get(i);
            LocalBasis basis = pathBases.get(i);

            for (Map.Entry<Vector, BlockData> entry : roadPreset.getBlocks().entrySet()) {
                Vector relativeToOrigin = entry.getKey().clone().subtract(presetOrigin);
                
                Vector rotatedOffset = basis.right().clone().multiply(relativeToOrigin.getX())
                                     .add(basis.up().clone().multiply(relativeToOrigin.getY()))
                                     .add(basis.forward().clone().multiply(relativeToOrigin.getZ()));

                Location worldBlockLocation = pathPoint.clone().add(rotatedOffset);
                
                worldBlocks.put(worldBlockLocation.getBlock().getLocation(), entry.getValue());
            }
        }

        Queue<BlockPlacementInfo> placementQueue = sortBlocksByDistance(worldBlocks, path);
        new BuildPlacementTask(plugin, playerUUID, placementQueue).runTask(plugin);
    }

    private List<LocalBasis> calculatePathBases(List<Location> path) {
        List<LocalBasis> bases = new ArrayList<>();
        if (path.isEmpty()) return bases;

        Vector lastRight = null;

        for (int i = 0; i < path.size(); i++) {
            Vector forward;
            if (i < path.size() - 1) {
                forward = path.get(i + 1).toVector().subtract(path.get(i).toVector());
            } else if (i > 0) {
                forward = path.get(i).toVector().subtract(path.get(i - 1).toVector());
            } else {
                forward = new Vector(0, 0, 1);
            }
            if (forward.lengthSquared() < 1e-6) forward = new Vector(0, 0, 1);
            forward.normalize();

            Vector up = new Vector(0, 1, 0);
            // ★★★★★ 修正点：右手座標系に準拠した、正しい「右」ベクトルの計算 ★★★★★
            Vector right = forward.clone().crossProduct(up).normalize();

            if (right.lengthSquared() < 1e-6) {
                right = lastRight != null ? lastRight.clone() : new Vector(1, 0, 0);
            }
            
            // 左右反転防止ロジック
            if (lastRight != null && right.dot(lastRight) < 0) {
                right.multiply(-1);
            }
            lastRight = right.clone();
            
            Vector finalForward = up.clone().crossProduct(right).normalize();

            bases.add(new LocalBasis(finalForward, up, right));
        }
        return bases;
    }
    
    private Queue<BlockPlacementInfo> sortBlocksByDistance(Map<Location, BlockData> worldBlocks, List<Location> path) {
        TreeMap<Integer, List<BlockPlacementInfo>> sortedBlocksByDistance = new TreeMap<>();
        List<Vector> pathVectors = path.stream().map(Location::toVector).collect(Collectors.toList());
        for (Map.Entry<Location, BlockData> entry : worldBlocks.entrySet()) {
            Location blockLoc = entry.getKey();
            double minDistanceSquared = Double.MAX_VALUE;
            for (Vector axisPoint : pathVectors) {
                double dx = blockLoc.getX() - axisPoint.getX();
                double dz = blockLoc.getZ() - axisPoint.getZ();
                minDistanceSquared = Math.min(minDistanceSquared, dx * dx + dz * dz);
            }
            int distance = (int) Math.round(Math.sqrt(minDistanceSquared));
            sortedBlocksByDistance.computeIfAbsent(distance, k -> new ArrayList<>()).add(new BlockPlacementInfo(blockLoc, entry.getValue()));
        }
        Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>();
        sortedBlocksByDistance.descendingKeySet().forEach(distance -> placementQueue.addAll(sortedBlocksByDistance.get(distance)));
        return placementQueue;
    }
}

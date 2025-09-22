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
        if (path == null || path.size() < 2) {
            return;
        }

        List<LocalBasis> pathBases = calculatePathBases(path);
        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();
        Vector presetOrigin = roadPreset.getAxisPath().isEmpty() ? new Vector(0, 0, 0) : roadPreset.getAxisPath().get(0);
        
        // プリセットをZ=0の断面として扱う
        List<Map.Entry<Vector, BlockData>> presetCrossSection = roadPreset.getBlocks().entrySet().stream()
                .filter(entry -> entry.getKey().clone().subtract(presetOrigin).getBlockZ() == 0)
                .collect(Collectors.toList());

        if (presetCrossSection.isEmpty()) {
            // Z=0に断面がない場合、最もZが小さい断面を代表として使用
            Optional<Integer> minZ = roadPreset.getBlocks().keySet().stream()
                    .map(v -> v.clone().subtract(presetOrigin).getBlockZ())
                    .min(Integer::compareTo);
            
            if (minZ.isPresent()) {
                int finalMinZ = minZ.get();
                presetCrossSection = roadPreset.getBlocks().entrySet().stream()
                    .filter(entry -> entry.getKey().clone().subtract(presetOrigin).getBlockZ() == finalMinZ)
                    .collect(Collectors.toList());
            } else {
                 return; // プリセットが空
            }
        }
        
        // 経路のセグメントごとに断面を押し出す
        for (int i = 0; i < path.size() - 1; i++) {
            Location startPoint = path.get(i);
            Location endPoint = path.get(i + 1);
            LocalBasis startBasis = pathBases.get(i);
            LocalBasis endBasis = pathBases.get(i + 1);

            for (Map.Entry<Vector, BlockData> blockEntry : presetCrossSection) {
                Vector relativePos = blockEntry.getKey().clone().subtract(presetOrigin);
                BlockData blockData = blockEntry.getValue();

                Vector startOffset = startBasis.right().clone().multiply(relativePos.getX())
                                   .add(startBasis.up().clone().multiply(relativePos.getY()));
                Location startBlockLoc = startPoint.clone().add(startOffset);

                Vector endOffset = endBasis.right().clone().multiply(relativePos.getX())
                                 .add(endBasis.up().clone().multiply(relativePos.getY()));
                Location endBlockLoc = endPoint.clone().add(endOffset);

                fillLine3D(startBlockLoc, endBlockLoc, blockData, worldBlocks);
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

    private void fillLine3D(Location start, Location end, BlockData data, Map<Location, BlockData> worldBlocks) {
        int x1 = start.getBlockX(), y1 = start.getBlockY(), z1 = start.getBlockZ();
        int x2 = end.getBlockX(), y2 = end.getBlockY(), z2 = end.getBlockZ();
        worldBlocks.put(new Location(start.getWorld(), x1, y1, z1), data);
        int dx = Math.abs(x2-x1), dy = Math.abs(y2-y1), dz = Math.abs(z2-z1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, sz = z1 < z2 ? 1 : -1;
        int i = 1;
        if (dx >= dy && dx >= dz) {
            int err1 = 2*dy - dx, err2 = 2*dz - dx;
            for (; i <= dx; i++) {
                worldBlocks.put(new Location(start.getWorld(), x1, y1, z1), data);
                if (err1 > 0) { y1 += sy; err1 -= 2*dx; }
                if (err2 > 0) { z1 += sz; err2 -= 2*dx; }
                err1 += 2*dy; err2 += 2*dz; x1 += sx;
            }
        } else if (dy >= dx && dy >= dz) {
            int err1 = 2*dx-dy, err2 = 2*dz-dy;
            for (; i <= dy; i++) {
                worldBlocks.put(new Location(start.getWorld(), x1, y1, z1), data);
                if (err1 > 0) { x1 += sx; err1 -= 2*dy; }
                if (err2 > 0) { z1 += sz; err2 -= 2*dy; }
                err1 += 2*dx; err2 += 2*dz; y1 += sy;
            }
        } else {
            int err1 = 2*dx-dz, err2 = 2*dy-dz;
            for (; i <= dz; i++) {
                worldBlocks.put(new Location(start.getWorld(), x1, y1, z1), data);
                if (err1 > 0) { x1 += sx; err1 -= 2*dz; }
                if (err2 > 0) { y1 += sy; err2 -= 2*dz; }
                err1 += 2*dx; err2 += 2*dy; z1 += sz;
            }
        }
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

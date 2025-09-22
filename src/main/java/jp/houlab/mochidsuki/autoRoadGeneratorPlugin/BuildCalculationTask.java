package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
    private final PresetManager presetManager;

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset, PresetManager presetManager) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.roadPreset = roadPreset;
        this.presetManager = presetManager;
    }

    // 直交基底を保持するためのヘルパークラス
    private static class OrthonormalBasis {
        final Vector right;
        final Vector up;
        final Vector forward;

        OrthonormalBasis(Vector right, Vector up, Vector forward) {
            this.right = right;
            this.up = up;
            this.forward = forward;
        }
    }

    @Override
    public void run() {
        // 最終的なブロック設置計画 (重複を避けるためにMapを使用)
        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();

        // 経路が空の場合は処理を中断
        if (routeSession.getCalculatedPath().isEmpty()) {
            Bukkit.getLogger().warning("Calculated path is empty for player " + playerUUID);
            return;
        }

        // プリセットの基準点からの相対座標リスト
        Map<Vector, BlockData> presetBlocks = roadPreset.getBlocks();
        List<Vector> presetAxisPath = roadPreset.getAxisPath();

        // プリセットの軸の最初の点を基準として、プリセットの軸の相対座標をワールド座標に変換するためのオフセットを計算
        // プリセットの軸の最初の点と、経路の現在の点を合わせる
        Vector presetAxisStartRelative = presetAxisPath.isEmpty() ? new Vector(0,0,0) : presetAxisPath.get(0);

        // --- ステップ1: 経路の各点における直交基底を事前計算 --- 
        List<OrthonormalBasis> pathBases = new ArrayList<>();
        for (int i = 0; i < routeSession.getCalculatedPath().size(); i++) {
            Location currentPathLoc = routeSession.getCalculatedPath().get(i);
            Location nextPathLoc;

            // 経路の最後の点の場合のnextPathLocを堅牢に決定
            if (i + 1 < routeSession.getCalculatedPath().size()) {
                nextPathLoc = routeSession.getCalculatedPath().get(i + 1);
            } else {
                // 最後の点の場合、前のセグメントの方向を使用
                if (i > 0) {
                    Location prevPathLoc = routeSession.getCalculatedPath().get(i - 1);
                    Vector lastSegmentDirection = currentPathLoc.toVector().subtract(prevPathLoc.toVector());
                    if (lastSegmentDirection.lengthSquared() < 1e-6) {
                        nextPathLoc = currentPathLoc.clone().add(new Vector(0, 0, 1)); // 前のセグメントも退化している場合
                    } else {
                        nextPathLoc = currentPathLoc.clone().add(lastSegmentDirection.normalize()); // 最後の点の方向を延長
                    }
                } else {
                    // 経路が1点しかない場合、デフォルトの方向を設定
                    nextPathLoc = currentPathLoc.clone().add(new Vector(0, 0, 1)); // デフォルトで+Z方向
                }
            }

            // 経路の進行方向ベクトルを計算
            Vector rawForwardDirection = nextPathLoc.toVector().subtract(currentPathLoc.toVector());
            if (rawForwardDirection.lengthSquared() < 1e-6) {
                rawForwardDirection = new Vector(0, 0, 1); // デフォルトの方向
            }

            // プリセットのローカル座標系を構築
            // プリセットのY軸は常にワールドのY軸に合わせる
            Vector presetUp = new Vector(0, 1, 0); 

            // プリセットのZ軸 (前方向) は、経路の進行方向の水平成分を使用
            Vector presetForward = new Vector(rawForwardDirection.getX(), 0, rawForwardDirection.getZ());
            if (presetForward.lengthSquared() < 1e-6) {
                // 経路が垂直な場合、デフォルトの水平方向 (ワールドの北) を使用
                presetForward = new Vector(0, 0, 1); 
            }
            presetForward.normalize();

            // プリセットのX軸 (右方向) を計算 (presetUp と presetForward に垂直)
            Vector presetRight = presetUp.clone().crossProduct(presetForward);
            if (presetRight.lengthSquared() < 1e-6) {
                // このケースは通常発生しないはずだが、念のためワールドの東をデフォルトとする
                presetRight = new Vector(1, 0, 0);
            }
            presetRight.normalize();

            pathBases.add(new OrthonormalBasis(presetRight, presetUp, presetForward));
        }

        // --- ステップ2: プリセットの各ブロックを経路に沿って配置 --- 
        for (Map.Entry<Vector, BlockData> entry : presetBlocks.entrySet()) {
            Vector relativePresetBlockPos = entry.getKey(); // (px, py, pz)
            BlockData blockData = entry.getValue();

            // プリセットの相対座標を、プリセットの軸の開始点が原点になるように調整
            Vector adjustedRelative = relativePresetBlockPos.clone().subtract(presetAxisStartRelative); // (ax, ay, az)

            // adjustedRelative.getZ() を経路上の距離として使用
            double targetPathDistance = adjustedRelative.getZ(); // プリセットのZ座標をそのまま経路上の距離とする

            // 経路の長さを超えないようにクランプ
            int pathLength = routeSession.getCalculatedPath().size();
            if (pathLength == 0) continue; // 経路が空の場合はスキップ

            double scaledPathDistance = targetPathDistance; // 1ブロック = 1経路点と仮定
            if (scaledPathDistance < 0) scaledPathDistance = 0;
            // 最後のセグメントの終点を含まないように微調整 (pathLength-1 は最後の点のインデックス)
            if (scaledPathDistance >= pathLength - 1) scaledPathDistance = pathLength - 1 - 1e-6;

            int basePathIndex = (int) Math.floor(scaledPathDistance);
            double interpolationFactor = scaledPathDistance - basePathIndex;

            // 経路が1点しかない場合の処理
            if (pathLength == 1) {
                basePathIndex = 0;
                interpolationFactor = 0;
            }

            Location p1 = routeSession.getCalculatedPath().get(basePathIndex);
            Location p2 = (basePathIndex + 1 < pathLength) ? routeSession.getCalculatedPath().get(basePathIndex + 1) : p1; // 最後の点の場合はP1=P2
            OrthonormalBasis basis1 = pathBases.get(basePathIndex);
            OrthonormalBasis basis2 = (basePathIndex + 1 < pathLength) ? pathBases.get(basePathIndex + 1) : basis1; // 最後の点の場合はBasis1=Basis2

            // 位置と基底を線形補間
            Location interpolatedPathLoc = lerp(p1, p2, interpolationFactor);
            Vector interpolatedRight = lerp(basis1.right, basis2.right, interpolationFactor).normalize();
            Vector interpolatedUp = lerp(basis1.up, basis2.up, interpolationFactor).normalize();
            // interpolatedForward はここでは直接使用しないが、基底の整合性のため補間しておく
            // Vector interpolatedForward = lerp(basis1.forward, basis2.forward, interpolationFactor).normalize();

            // 回転と移動を適用
            // adjustedRelative のX成分をinterpolatedRight、Y成分をinterpolatedUpに対応させる
            // Z成分は経路上の位置決定に使用されたため、ここでは回転に含めない
            Vector rotatedOffset = interpolatedRight.clone().multiply(adjustedRelative.getX())
                                   .add(interpolatedUp.clone().multiply(adjustedRelative.getY()));

            // 最終的なワールド座標は、補間された経路上の点 + 回転されたオフセット
            Vector worldBlockVector = interpolatedPathLoc.toVector().add(rotatedOffset).toBlockVector();
            Location worldBlockLocation = new Location(interpolatedPathLoc.getWorld(), worldBlockVector.getX(), worldBlockVector.getY(), worldBlockVector.getZ());
            worldBlocks.put(worldBlockLocation, blockData);
        }

        // ステップB: 「外側から内側へ」の設置順ソート
        // 1. 距離の計算とグルーピング
        // TreeMap<Integer, List<BlockPlacementInfo>>: キーは中心軸からの距離、値はその距離に属するブロックのリスト
        TreeMap<Integer, List<BlockPlacementInfo>> sortedBlocksByDistance = new TreeMap<>();

        // 経路のワールド座標リスト (中心軸からの距離計算用)
        List<Vector> worldAxisPathVectors = routeSession.getCalculatedPath().stream()
                .map(Location::toVector)
                .collect(Collectors.toList());

        for (Map.Entry<Location, BlockData> entry : worldBlocks.entrySet()) {
            Location blockLoc = entry.getKey();
            BlockData blockData = entry.getValue();

            double minDistanceSquared = Double.MAX_VALUE;
            for (Vector axisPoint : worldAxisPathVectors) {
                // ブロックから軸への垂直距離を計算 (Y軸方向は無視)
                Vector blockPos2D = new Vector(blockLoc.getX(), 0, blockLoc.getZ());
                Vector axisPoint2D = new Vector(axisPoint.getX(), 0, axisPoint.getZ());
                minDistanceSquared = Math.min(minDistanceSquared, blockPos2D.distanceSquared(axisPoint2D));
            }
            int distance = (int) Math.round(Math.sqrt(minDistanceSquared));

            sortedBlocksByDistance.computeIfAbsent(distance, k -> new ArrayList<>()).add(new BlockPlacementInfo(blockLoc, blockData));
        }

        // 2. 最終的なキューの作成 (降順: 外側から内側へ)
        Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>();
        sortedBlocksByDistance.descendingKeySet().forEach(distance -> {
            placementQueue.addAll(sortedBlocksByDistance.get(distance));
        });

        // 計算完了後、同期タスクをスケジュール
        new BuildPlacementTask(plugin, playerUUID, placementQueue).runTask(plugin);
    }

    // Locationの線形補間ヘルパーメソッド
    private Location lerp(Location loc1, Location loc2, double t) {
        if (!loc1.getWorld().equals(loc2.getWorld())) {
            // ワールドが異なる場合は補間せず、最初の位置を返すかエラー処理
            return loc1;
        }
        double x = loc1.getX() * (1 - t) + loc2.getX() * t;
        double y = loc1.getY() * (1 - t) + loc2.getY() * t;
        double z = loc1.getZ() * (1 - t) + loc2.getZ() * t;
        return new Location(loc1.getWorld(), x, y, z);
    }

    // Vectorの線形補間ヘルパーメソッド
    private Vector lerp(Vector vec1, Vector vec2, double t) {
        double x = vec1.getX() * (1 - t) + vec2.getX() * t;
        double y = vec1.getY() * (1 - t) + vec2.getY() * t;
        double z = vec1.getZ() * (1 - t) + vec2.getZ() * t;
        return new Vector(x, y, z);
    }
}

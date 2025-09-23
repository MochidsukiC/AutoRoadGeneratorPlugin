package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.type.Slab;
import org.bukkit.util.Vector;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.checkerframework.checker.units.qual.A;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BuildCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final RoadPreset roadPreset;

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

        List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
        List<BlockPlacementInfo> originalBlocks = new ArrayList<>();

        // TODO: ここに手動で道路建築アルゴリズムを実装してください
        //
        // 利用可能なデータ:
        // - path: RouteCalculatorが生成した高密度な経路点のリスト（0.5ブロック間隔）
        //   各Locationにはヨー角（接線方向）とピッチ角（傾斜）が設定済み
        //
        // プリセットデータ（新スライスベース形式）:
        // - roadPreset.isSliceBased(): スライスベース形式かどうかを確認
        // - roadPreset.getSlices(): X軸方向のスライスリスト
        // - roadPreset.getLengthX(): X軸（進行方向）の長さ
        // - roadPreset.getWidthZ(): Z軸（横方向）の幅
        // - roadPreset.getHeightY(): Y軸（高さ方向）の高さ
        //
        // プリセットの座標範囲（軸からの相対座標）:
        // - roadPreset.getMinZ(), roadPreset.getMaxZ(): Z方向の範囲
        // - roadPreset.getMinY(), roadPreset.getMaxY(): Y方向の範囲
        //
        // for文での使用例（軸からの相対座標）:
        // for (int z = roadPreset.getMinZ(); z <= roadPreset.getMaxZ(); z++) {
        //     for (int y = roadPreset.getMinY(); y <= roadPreset.getMaxY(); y++) {
        //         // 軸からの相対座標でブロック取得
        //         BlockData block = slice.getBlockRelativeToAxis(z, y);
        //         if (block != null) {
        //             // ブロック配置処理
        //         }
        //     }
        // }
        //
        // 各スライス(PresetSlice)の使い方:
        // - slice.getXPosition(): このスライスのX座標（進行方向位置）
        // - slice.getBlock(z, y): 指定した(z,y)位置のBlockDataを取得
        // - slice.getWidthZ(), slice.getHeightY(): スライスのサイズ
        //
        // 座標系:
        // - X軸: 道路の進行方向（RouteCalculatorが生成した経路の方向）
        // - Z軸: 道路の横方向（左右）
        // - Y軸: 高さ方向（上下）
        //
        // 各経路点でのヨー角とピッチ角からベクトルを算出する方法:
        // for (Location pathPoint : path) {
        //     float yaw = pathPoint.getYaw();   // 進行方向のヨー角（度）
        //     float pitch = pathPoint.getPitch(); // 進行方向のピッチ角（度）
        //     float rightYaw = yaw + 90f;       // 右方向のヨー角（+90度回転）
        //
        //     // 進行方向ベクトル（ピッチを考慮）
        //     double forwardX = Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
        //     double forwardY = -Math.sin(Math.toRadians(pitch));
        //     double forwardZ = Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
        //     Vector forwardVector = new Vector(forwardX, forwardY, forwardZ);
        //
        //     // 右方向ベクトル（水平面での法線）
        //     double rightX = Math.cos(Math.toRadians(rightYaw));
        //     double rightZ = Math.sin(Math.toRadians(rightYaw));
        //     Vector rightVector = new Vector(rightX, 0, rightZ);
        //
        //     // 上方向ベクトル（進行方向と右方向の外積で計算）
        //     Vector upVector = rightVector.clone().crossProduct(forwardVector).normalize();
        // }
        //
        // ブロック配置リストへの追加方法:
        // worldBlocks.add(new BlockPlacementInfo(worldLocation, blockData));
        //
        // 例:
        // Location worldPos = new Location(world, worldX, worldY, worldZ);
        // worldBlocks.add(new BlockPlacementInfo(worldPos, blockData));
        //
        // 注意: リストの0番目から順番に設置されるため、設置順序が重要な場合は
        // 適切な順序でaddしてください。

        boolean plus = false;
        for (int zz = 0; zz <= roadPreset.getWidthZ()/2+1; zz++) {
            for(int j = 0;j<2;j++) {
                int z;
                if(j<1){
                    z = roadPreset.getMaxZ()-zz;
                }else{
                    z = roadPreset.getMinZ()+zz;
                }

                float x = 0;
                for (Location pathPoint : path) {
                    float yaw = pathPoint.getYaw();   // 進行方向のヨー角（度）
                    float pitch = pathPoint.getPitch(); // 進行方向のピッチ角（度）
                    float rightYaw = yaw + 90f;       // 右方向のヨー角（+90度回転）

                    // 進行方向ベクトル（ピッチを考慮）
                    double forwardX = Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
                    double forwardY = -Math.sin(Math.toRadians(pitch));
                    double forwardZ = Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
                    Vector forwardVector = new Vector(forwardX, forwardY, forwardZ).normalize();

                    // 右方向ベクトル（水平面での法線）
                    double rightX = Math.cos(Math.toRadians(rightYaw));
                    double rightZ = Math.sin(Math.toRadians(rightYaw));
                    Vector rightVector = new Vector(rightX, 0, rightZ).normalize();

                    // 上方向ベクトル（進行方向と右方向の外積で計算）
                    Vector upVector = rightVector.clone().crossProduct(forwardVector).normalize();

                    out:
                    for (int y = roadPreset.getMinY(); y <= roadPreset.getMaxY(); y++) {
                        RoadPreset.PresetSlice slice = roadPreset.getSlices().get((int) x);
                        BlockData blockData = slice.getBlockRelativeToAxis(z, y, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());

                        if (blockData != null) {
                            Location worldLocation = pathPoint.clone().add(rightVector.clone().multiply(z)).add(0, y, 0);

                            // BlockDataのクローンを作成して参照共有を防ぐ
                            BlockData clonedBlockData = blockData.clone();

                            // パスポイントの向きに合わせてBlockDataを回転（Facingも一緒に回転）
                            clonedBlockData = BlockRotationUtil.rotateBlockData(clonedBlockData, Math.toRadians(yaw));

                            // 坂を滑らかにするために、ハーフブロックの高さを調整
                            if(clonedBlockData instanceof Slab){
                                Slab slab = (Slab) clonedBlockData;

                                // 上のブロックが空気かどうかをチェック
                                BlockData blockAbove = null;
                                if (y + 1 <= roadPreset.getMaxY()) {
                                    blockAbove = slice.getBlockRelativeToAxis(z, y + 1, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());
                                }

                                // ハーフブロックで上が空気の場合、地形に合わせて調整
                                if (blockAbove == null || blockAbove.getMaterial() == Material.AIR) { // 上が空気または範囲外

                                    // 地面の高さと比較してスラブタイプを決定
                                    int groundY = (int) Math.floor(worldLocation.getY());
                                    double heightAboveGround = worldLocation.getY() - groundY;

                                    // 地面から0.5ブロック以下の場合はBOTTOMスラブ、それ以外はDOUBLE

                                    if(((Slab)blockData).getType() == Slab.Type.BOTTOM) {
                                        if (heightAboveGround <= 0.5) {
                                            break out;
                                        }else {
                                            slab.setType(Slab.Type.BOTTOM);
                                        }
                                    }else {
                                        if (heightAboveGround < 0.5) {
                                            slab.setType(Slab.Type.BOTTOM);
                                        } else {
                                            slab.setType(Slab.Type.DOUBLE);
                                        }
                                    }


                                } else {
                                    // 上にブロックがある場合は通常のダブルスラブ
                                    slab.setType(Slab.Type.DOUBLE);
                                }
                            }

                            originalBlocks.add(new BlockPlacementInfo(worldLocation,worldLocation.getBlock().getBlockData()));
                            worldBlocks.add(new BlockPlacementInfo(worldLocation, clonedBlockData));

                        }
                    }

                    x += 0.5f;
                    if (x >= roadPreset.getLengthX()) x = 0;
                }
            }
        }


        BuildHistoryManager.addBuildHistory(playerUUID,originalBlocks);
        Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(worldBlocks);
        new BuildPlacementTask(plugin, playerUUID, placementQueue).runTaskTimer(plugin,1,1);
    }

}
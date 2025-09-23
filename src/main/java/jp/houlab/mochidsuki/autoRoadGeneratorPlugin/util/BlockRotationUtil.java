package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.bukkit.Material;

/**
 * ブロックの向き（Facing）を回転させるためのユーティリティクラス
 */
public class BlockRotationUtil {

    /**
     * BlockDataを指定された角度（Y軸回転）で回転させます
     * @param blockData 回転させるBlockData
     * @param rotationAngle 回転角度（ラジアン）
     * @return 回転後のBlockData
     */
    public static BlockData rotateBlockData(BlockData blockData, double rotationAngle) {
        if (blockData == null) {
            return null;
        }

        BlockData rotatedData = blockData.clone();

        // 角度を90度単位に変換（Minecraft的に意味のある回転）
        int quarterTurns = getQuarterTurns(rotationAngle);

        if (quarterTurns == 0) {
            return rotatedData; // 回転不要
        }

        // Stairs インターフェースを実装するブロック（階段は特別に先に処理）
        if (rotatedData instanceof Stairs) {
            Stairs stairs = (Stairs) rotatedData;
            BlockFace currentFacing = stairs.getFacing();
            BlockFace newFacing = rotateBlockFace(currentFacing, quarterTurns);
            if (stairs.getFaces().contains(newFacing)) {
                stairs.setFacing(newFacing);
            }
        }
        // Directional インターフェースを実装するブロック（階段以外）
        else if (rotatedData instanceof Directional) {
            Directional directional = (Directional) rotatedData;
            BlockFace currentFacing = directional.getFacing();
            BlockFace newFacing = rotateBlockFace(currentFacing, quarterTurns);
            if (directional.getFaces().contains(newFacing)) {
                directional.setFacing(newFacing);
            }
        }

        // Orientable インターフェースを実装するブロック（原木など）
        if (rotatedData instanceof Orientable) {
            Orientable orientable = (Orientable) rotatedData;
            org.bukkit.Axis currentAxis = orientable.getAxis();
            org.bukkit.Axis newAxis = rotateAxis(currentAxis, quarterTurns);
            if (orientable.getAxes().contains(newAxis)) {
                orientable.setAxis(newAxis);
            }
        }

        // Rail インターフェースを実装するブロック（レール）
        if (rotatedData instanceof Rail) {
            Rail rail = (Rail) rotatedData;
            Rail.Shape currentShape = rail.getShape();
            Rail.Shape newShape = rotateRailShape(currentShape, quarterTurns);
            if (rail.getShapes().contains(newShape)) {
                rail.setShape(newShape);
            }
        }

        // Wall インターフェースを実装するブロック（壁）
        if (rotatedData instanceof Wall) {
            Wall wall = (Wall) rotatedData;
            rotateWallConnections(wall, quarterTurns);
        }

        // MultipleFacing インターフェースを実装するブロック（ガラス板、鉄格子など）
        if (rotatedData instanceof MultipleFacing) {
            MultipleFacing multipleFacing = (MultipleFacing) rotatedData;
            rotateMultipleFacingConnections(multipleFacing, quarterTurns);
        }

        // Rotatable インターフェースを実装するブロック（立て看板、プレイヤーヘッドなど）
        if (rotatedData instanceof Rotatable) {
            Rotatable rotatable = (Rotatable) rotatedData;
            BlockFace currentRotation = rotatable.getRotation();
            BlockFace newRotation;

            // プレイヤーヘッドの場合は特別な回転処理
            if (isPlayerHead(rotatedData)) {
                newRotation = rotatePlayerHead(currentRotation, quarterTurns);
            } else {
                newRotation = rotateBlockFace(currentRotation, quarterTurns);
            }

            rotatable.setRotation(newRotation);
        }

        return rotatedData;
    }

    /**
     * ラジアンの角度を90度単位のクォーター回転数に変換
     * @param rotationAngle 回転角度（ラジアン）
     * @return 90度単位の回転数（0-3）
     */
    private static int getQuarterTurns(double rotationAngle) {
        // ラジアンを度に変換
        double degrees = Math.toDegrees(rotationAngle);

        // 最も近い90度の倍数に丸める
        int quarterTurns = (int) Math.round(degrees / 90.0);

        // 0-3の範囲に正規化
        return ((quarterTurns % 4) + 4) % 4;
    }

    /**
     * BlockFaceを指定されたクォーター回転数で回転
     * @param face 回転させるBlockFace
     * @param quarterTurns 90度単位の回転数
     * @return 回転後のBlockFace
     */
    private static BlockFace rotateBlockFace(BlockFace face, int quarterTurns) {
        if (quarterTurns == 0) {
            return face;
        }

        // 16方向の立て看板回転に対応
        BlockFace[] rotationOrder = {
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST
        };

        // 現在の方向のインデックスを見つける
        int currentIndex = -1;
        for (int i = 0; i < rotationOrder.length; i++) {
            if (rotationOrder[i] == face) {
                currentIndex = i;
                break;
            }
        }

        // 16方向の回転表にない場合は、基本4方向の回転を使用
        if (currentIndex == -1) {
            switch (face) {
                case NORTH:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.EAST;
                        case 2: return BlockFace.SOUTH;
                        case 3: return BlockFace.WEST;
                        default: return BlockFace.NORTH;
                    }
                case EAST:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.SOUTH;
                        case 2: return BlockFace.WEST;
                        case 3: return BlockFace.NORTH;
                        default: return BlockFace.EAST;
                    }
                case SOUTH:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.WEST;
                        case 2: return BlockFace.NORTH;
                        case 3: return BlockFace.EAST;
                        default: return BlockFace.SOUTH;
                    }
                case WEST:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.NORTH;
                        case 2: return BlockFace.EAST;
                        case 3: return BlockFace.SOUTH;
                        default: return BlockFace.WEST;
                    }
                // 上下向きは回転しない
                case UP:
                case DOWN:
                default:
                    return face;
            }
        }

        // 16方向回転：90度は4ステップに相当
        int newIndex = (currentIndex + (quarterTurns * 4)) % rotationOrder.length;
        if (newIndex < 0) {
            newIndex += rotationOrder.length;
        }

        return rotationOrder[newIndex];
    }

    /**
     * Orientableの軸を回転（原木など）
     * @param axis 回転させる軸
     * @param quarterTurns 90度単位の回転数
     * @return 回転後の軸
     */
    private static org.bukkit.Axis rotateAxis(org.bukkit.Axis axis, int quarterTurns) {
        if (quarterTurns == 0) {
            return axis;
        }

        // Y軸は回転しない、X-Z間での回転のみ
        switch (axis) {
            case X:
                return (quarterTurns % 2 == 1) ? org.bukkit.Axis.Z : org.bukkit.Axis.X;
            case Z:
                return (quarterTurns % 2 == 1) ? org.bukkit.Axis.X : org.bukkit.Axis.Z;
            case Y:
            default:
                return axis;
        }
    }

    /**
     * レールの形状を回転
     * @param shape 回転させるレール形状
     * @param quarterTurns 90度単位の回転数
     * @return 回転後のレール形状
     */
    private static Rail.Shape rotateRailShape(Rail.Shape shape, int quarterTurns) {
        if (quarterTurns == 0) {
            return shape;
        }

        switch (shape) {
            case NORTH_SOUTH:
                return (quarterTurns % 2 == 1) ? Rail.Shape.EAST_WEST : Rail.Shape.NORTH_SOUTH;
            case EAST_WEST:
                return (quarterTurns % 2 == 1) ? Rail.Shape.NORTH_SOUTH : Rail.Shape.EAST_WEST;
            case ASCENDING_EAST:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.ASCENDING_SOUTH;
                    case 2: return Rail.Shape.ASCENDING_WEST;
                    case 3: return Rail.Shape.ASCENDING_NORTH;
                    default: return Rail.Shape.ASCENDING_EAST;
                }
            case ASCENDING_WEST:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.ASCENDING_NORTH;
                    case 2: return Rail.Shape.ASCENDING_EAST;
                    case 3: return Rail.Shape.ASCENDING_SOUTH;
                    default: return Rail.Shape.ASCENDING_WEST;
                }
            case ASCENDING_NORTH:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.ASCENDING_EAST;
                    case 2: return Rail.Shape.ASCENDING_SOUTH;
                    case 3: return Rail.Shape.ASCENDING_WEST;
                    default: return Rail.Shape.ASCENDING_NORTH;
                }
            case ASCENDING_SOUTH:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.ASCENDING_WEST;
                    case 2: return Rail.Shape.ASCENDING_NORTH;
                    case 3: return Rail.Shape.ASCENDING_EAST;
                    default: return Rail.Shape.ASCENDING_SOUTH;
                }
            case SOUTH_EAST:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.SOUTH_WEST;
                    case 2: return Rail.Shape.NORTH_WEST;
                    case 3: return Rail.Shape.NORTH_EAST;
                    default: return Rail.Shape.SOUTH_EAST;
                }
            case SOUTH_WEST:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.NORTH_WEST;
                    case 2: return Rail.Shape.NORTH_EAST;
                    case 3: return Rail.Shape.SOUTH_EAST;
                    default: return Rail.Shape.SOUTH_WEST;
                }
            case NORTH_WEST:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.NORTH_EAST;
                    case 2: return Rail.Shape.SOUTH_EAST;
                    case 3: return Rail.Shape.SOUTH_WEST;
                    default: return Rail.Shape.NORTH_WEST;
                }
            case NORTH_EAST:
                switch (quarterTurns % 4) {
                    case 1: return Rail.Shape.SOUTH_EAST;
                    case 2: return Rail.Shape.SOUTH_WEST;
                    case 3: return Rail.Shape.NORTH_WEST;
                    default: return Rail.Shape.NORTH_EAST;
                }
            default:
                return shape;
        }
    }

    /**
     * 壁の接続状態を回転
     * @param wall 回転させる壁ブロック
     * @param quarterTurns 90度単位の回転数
     */
    private static void rotateWallConnections(Wall wall, int quarterTurns) {
        if (quarterTurns == 0) {
            return;
        }

        // 現在の接続状態を取得
        Wall.Height northHeight = wall.getHeight(BlockFace.NORTH);
        Wall.Height eastHeight = wall.getHeight(BlockFace.EAST);
        Wall.Height southHeight = wall.getHeight(BlockFace.SOUTH);
        Wall.Height westHeight = wall.getHeight(BlockFace.WEST);

        // 回転数に応じて接続状態を回転
        switch (quarterTurns % 4) {
            case 1: // 90度時計回り
                wall.setHeight(BlockFace.NORTH, westHeight);
                wall.setHeight(BlockFace.EAST, northHeight);
                wall.setHeight(BlockFace.SOUTH, eastHeight);
                wall.setHeight(BlockFace.WEST, southHeight);
                break;
            case 2: // 180度回転
                wall.setHeight(BlockFace.NORTH, southHeight);
                wall.setHeight(BlockFace.EAST, westHeight);
                wall.setHeight(BlockFace.SOUTH, northHeight);
                wall.setHeight(BlockFace.WEST, eastHeight);
                break;
            case 3: // 270度時計回り（90度反時計回り）
                wall.setHeight(BlockFace.NORTH, eastHeight);
                wall.setHeight(BlockFace.EAST, southHeight);
                wall.setHeight(BlockFace.SOUTH, westHeight);
                wall.setHeight(BlockFace.WEST, northHeight);
                break;
        }
    }

    /**
     * MultipleFacingの接続状態を回転（ガラス板、鉄格子など）
     * @param multipleFacing 回転させるMultipleFacingブロック
     * @param quarterTurns 90度単位の回転数
     */
    private static void rotateMultipleFacingConnections(MultipleFacing multipleFacing, int quarterTurns) {
        if (quarterTurns == 0) {
            return;
        }

        // 現在の接続状態を取得（水平方向のみ）
        boolean northConnection = multipleFacing.hasFace(BlockFace.NORTH);
        boolean eastConnection = multipleFacing.hasFace(BlockFace.EAST);
        boolean southConnection = multipleFacing.hasFace(BlockFace.SOUTH);
        boolean westConnection = multipleFacing.hasFace(BlockFace.WEST);

        // 水平方向の接続をクリア
        multipleFacing.setFace(BlockFace.NORTH, false);
        multipleFacing.setFace(BlockFace.EAST, false);
        multipleFacing.setFace(BlockFace.SOUTH, false);
        multipleFacing.setFace(BlockFace.WEST, false);

        // 回転数に応じて接続状態を設定
        switch (quarterTurns % 4) {
            case 1: // 90度時計回り
                multipleFacing.setFace(BlockFace.NORTH, westConnection);
                multipleFacing.setFace(BlockFace.EAST, northConnection);
                multipleFacing.setFace(BlockFace.SOUTH, eastConnection);
                multipleFacing.setFace(BlockFace.WEST, southConnection);
                break;
            case 2: // 180度回転
                multipleFacing.setFace(BlockFace.NORTH, southConnection);
                multipleFacing.setFace(BlockFace.EAST, westConnection);
                multipleFacing.setFace(BlockFace.SOUTH, northConnection);
                multipleFacing.setFace(BlockFace.WEST, eastConnection);
                break;
            case 3: // 270度時計回り（90度反時計回り）
                multipleFacing.setFace(BlockFace.NORTH, eastConnection);
                multipleFacing.setFace(BlockFace.EAST, southConnection);
                multipleFacing.setFace(BlockFace.SOUTH, westConnection);
                multipleFacing.setFace(BlockFace.WEST, northConnection);
                break;
        }
    }

    /**
     * ブロックがプレイヤーヘッドかどうかを判定
     * @param blockData 判定するBlockData
     * @return プレイヤーヘッドの場合true
     */
    private static boolean isPlayerHead(BlockData blockData) {
        Material material = blockData.getMaterial();
        return material == Material.PLAYER_HEAD || material == Material.PLAYER_WALL_HEAD;
    }

    /**
     * プレイヤーヘッドの回転処理
     * @param currentRotation 現在の回転
     * @param quarterTurns 90度単位の回転数
     * @return 回転後のBlockFace
     */
    private static BlockFace rotatePlayerHead(BlockFace currentRotation, int quarterTurns) {
        // プレイヤーヘッドは16方向回転が可能
        BlockFace[] headRotationOrder = {
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST
        };

        // 現在の方向のインデックスを見つける
        int currentIndex = -1;
        for (int i = 0; i < headRotationOrder.length; i++) {
            if (headRotationOrder[i] == currentRotation) {
                currentIndex = i;
                break;
            }
        }

        // 見つからない場合は基本4方向として処理
        if (currentIndex == -1) {
            switch (currentRotation) {
                case NORTH:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.EAST;
                        case 2: return BlockFace.SOUTH;
                        case 3: return BlockFace.WEST;
                        default: return BlockFace.NORTH;
                    }
                case EAST:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.SOUTH;
                        case 2: return BlockFace.WEST;
                        case 3: return BlockFace.NORTH;
                        default: return BlockFace.EAST;
                    }
                case SOUTH:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.WEST;
                        case 2: return BlockFace.NORTH;
                        case 3: return BlockFace.EAST;
                        default: return BlockFace.SOUTH;
                    }
                case WEST:
                    switch (quarterTurns % 4) {
                        case 1: return BlockFace.NORTH;
                        case 2: return BlockFace.EAST;
                        case 3: return BlockFace.SOUTH;
                        default: return BlockFace.WEST;
                    }
                default:
                    return currentRotation;
            }
        }

        // 16方向回転：90度は4ステップに相当
        int newIndex = (currentIndex + (quarterTurns * 4)) % headRotationOrder.length;
        if (newIndex < 0) {
            newIndex += headRotationOrder.length;
        }

        return headRotationOrder[newIndex];
    }
}
package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * String版のブロック回転ユーティリティ（完全にスレッドセーフ）
 */
public class StringBlockRotationUtil {

    private static final Logger logger = Logger.getLogger(StringBlockRotationUtil.class.getName());

    // 90度時計回りの回転マッピング
    private static final Map<String, String> FACING_ROTATION = new HashMap<>();
    private static final Map<String, String> AXIS_ROTATION = new HashMap<>();
    private static final Map<String, String> RAIL_SHAPE_ROTATION = new HashMap<>();
    private static final Map<String, String> STAIRS_SHAPE_ROTATION = new HashMap<>();

    static {
        // facingプロパティの回転
        FACING_ROTATION.put("north", "east");
        FACING_ROTATION.put("east", "south");
        FACING_ROTATION.put("south", "west");
        FACING_ROTATION.put("west", "north");

        // axisプロパティの回転
        AXIS_ROTATION.put("x", "z");
        AXIS_ROTATION.put("z", "x");
        // y-axis is unchanged by yaw rotation

        // shapeプロパティ（レール）の回転
        RAIL_SHAPE_ROTATION.put("north_south", "east_west");
        RAIL_SHAPE_ROTATION.put("east_west", "north_south");
        RAIL_SHAPE_ROTATION.put("ascending_north", "ascending_east");
        RAIL_SHAPE_ROTATION.put("ascending_east", "ascending_south");
        RAIL_SHAPE_ROTATION.put("ascending_south", "ascending_west");
        RAIL_SHAPE_ROTATION.put("ascending_west", "ascending_north");
        RAIL_SHAPE_ROTATION.put("north_east", "south_east");
        RAIL_SHAPE_ROTATION.put("south_east", "south_west");
        RAIL_SHAPE_ROTATION.put("south_west", "north_west");
        RAIL_SHAPE_ROTATION.put("north_west", "north_east");

        // shapeプロパティ（階段）の回転（90度時計回り）
        STAIRS_SHAPE_ROTATION.put("inner_left", "inner_right");
        STAIRS_SHAPE_ROTATION.put("inner_right", "outer_right");
        STAIRS_SHAPE_ROTATION.put("outer_left", "inner_left");
        STAIRS_SHAPE_ROTATION.put("outer_right", "outer_left");
        // straight は変更なし
    }

    /**
     * BlockDataStringを指定された角度で回転させます。
     * @param blockDataString 元のBlockData文字列
     * @param rotationAngle 回転角度（ラジアン）
     * @return 回転後のBlockData文字列
     */
    public static String rotateBlockDataString(String blockDataString, double rotationAngle) {
        if (blockDataString == null || blockDataString.isEmpty()) {
            return blockDataString;
        }

        try {
            // 角度を90度単位の回転回数に変換
            int quarterTurns = getQuarterTurns(rotationAngle);

            if (quarterTurns == 0) {
                return blockDataString;
            }

            String result = blockDataString;
            // quarterTurns回だけ90度回転を適用
            for (int i = 0; i < quarterTurns; i++) {
                result = rotateSingleStep(result);
            }

            return result;

        } catch (Exception e) {
            logger.severe("StringBlockRotationUtil.rotateBlockDataString failed for: '" + blockDataString +
                         "' with angle: " + Math.toDegrees(rotationAngle) + "°");
            logger.severe("Exception: " + e.getMessage());
            e.printStackTrace();
            // 失敗した場合は元の文字列を返す
            return blockDataString;
        }
    }

    /**
     * 1ステップ（90度）の回転を適用します。
     * @param blockDataString 入力BlockData文字列
     * @return 90度回転後のBlockData文字列
     */
    private static String rotateSingleStep(String blockDataString) {
        String result = blockDataString;

        // ブロックタイプ別に特化した処理
        if (result.contains("_stairs")) {
            // 階段専用処理
            result = rotateStairsProperties(result);
        } else if (result.contains("_rail")) {
            // レール専用処理
            result = rotateFacingProperty(result);
            result = rotateRailShapeProperty(result);
        } else {
            // その他のブロック
            result = rotateFacingProperty(result);
            result = rotateAxisProperty(result);
            result = rotateMultipleFacingProperties(result); // For walls/fences
        }

        return result;
    }

    /**
     * "facing"プロパティを90度回転させます。
     */
    private static String rotateFacingProperty(String blockDataString) {
        return rotateGenericProperty(blockDataString, "facing", FACING_ROTATION);
    }

    /**
     * "axis"プロパティを90度回転させます。
     */
    private static String rotateAxisProperty(String blockDataString) {
        return rotateGenericProperty(blockDataString, "axis", AXIS_ROTATION);
    }

    /**
     * "shape"プロパティ（レール）を90度回転させます。
     */
    private static String rotateRailShapeProperty(String blockDataString) {
        // レールのブロックIDを確認（_railで終わるもの）
        if (blockDataString.contains("_rail")) {
            return rotateGenericProperty(blockDataString, "shape", RAIL_SHAPE_ROTATION);
        }
        return blockDataString;
    }

    /**
     * 階段のすべてのプロパティを一括で90度回転させます。
     */
    private static String rotateStairsProperties(String blockDataString) {
        String result = blockDataString;

        // facingプロパティを回転
        result = rotateFacingProperty(result);

        // shapeプロパティを回転（存在する場合のみ）
        String shapeValue = extractPropertyValue(result, "shape");
        if (shapeValue != null) {
            String newShapeValue = STAIRS_SHAPE_ROTATION.get(shapeValue);
            if (newShapeValue != null) {
                result = result.replace("shape=" + shapeValue, "shape=" + newShapeValue);
            }
            // straightの場合はnewShapeValueがnullになるが、これは正しい（変更不要）
        }

        return result;
    }

    /**
     * "shape"プロパティ（階段）を90度回転させます。
     * @deprecated rotateStairsPropertiesを使用してください
     */
    private static String rotateStairsShapeProperty(String blockDataString) {
        // 階段のブロックIDを確認（_stairsで終わるもの）
        if (blockDataString.contains("_stairs")) {
            return rotateGenericProperty(blockDataString, "shape", STAIRS_SHAPE_ROTATION);
        }
        return blockDataString;
    }

    /**
     * 汎用的なプロパティ回転メソッド。
     */
    private static String rotateGenericProperty(String blockDataString, String propertyName, Map<String, String> rotationMap) {
        String value = extractPropertyValue(blockDataString, propertyName);
        if (value != null) {
            String newValue = rotationMap.get(value);
            if (newValue != null) {
                return blockDataString.replace(propertyName + "=" + value, propertyName + "=" + newValue);
            }
        }
        return blockDataString;
    }

    /**
     * 壁やフェンスなどの複数の方向プロパティ（north, east, south, west）を90度回転させます。
     */
    private static String rotateMultipleFacingProperties(String blockDataString) {
        if (!blockDataString.contains("north=") && !blockDataString.contains("east=") &&
            !blockDataString.contains("south=") && !blockDataString.contains("west=")) {
            return blockDataString;
        }

        // 現在の各方向の値を取得
        String northValue = extractPropertyValue(blockDataString, "north");
        String eastValue = extractPropertyValue(blockDataString, "east");
        String southValue = extractPropertyValue(blockDataString, "south");
        String westValue = extractPropertyValue(blockDataString, "west");

        // 一時的なプレフィックスを使用して、置換の連鎖を防ぐ
        String tempResult = blockDataString;
        if (northValue != null) tempResult = tempResult.replaceAll("north=[^,\\]]*", "TEMP_EAST=" + northValue);
        if (eastValue != null) tempResult = tempResult.replaceAll("east=[^,\\]]*", "TEMP_SOUTH=" + eastValue);
        if (southValue != null) tempResult = tempResult.replaceAll("south=[^,\\]]*", "TEMP_WEST=" + southValue);
        if (westValue != null) tempResult = tempResult.replaceAll("west=[^,\\]]*", "TEMP_NORTH=" + westValue);

        // 一時的なプレフィックスを元に戻す
        return tempResult
                .replace("TEMP_NORTH=", "north=")
                .replace("TEMP_EAST=", "east=")
                .replace("TEMP_SOUTH=", "south=")
                .replace("TEMP_WEST=", "west=");
    }

    /**
     * BlockData文字列から指定されたプロパティの値を抽出します。
     */
    private static String extractPropertyValue(String blockDataString, String property) {
        // Regex to find "property=value" where value does not contain ',' or ']'
        Matcher matcher = Pattern.compile(property + "=([^,\\]]*)").matcher(blockDataString);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 角度（ラジアン）を90度単位の回転回数（0〜3）に変換します。
     */
    private static int getQuarterTurns(double rotationAngle) {
        double degrees = Math.toDegrees(rotationAngle);
        // 角度を 0-360 の範囲に正規化
        degrees = (degrees % 360 + 360) % 360;

        // 最も近い90度の倍数に丸める
        if (degrees >= 315 || degrees < 45) {
            return 0; // 0度
        } else if (degrees >= 45 && degrees < 135) {
            return 1; // 90度
        } else if (degrees >= 135 && degrees < 225) {
            return 2; // 180度
        } else { // 225 <= degrees < 315
            return 3; // 270度
        }
    }
}

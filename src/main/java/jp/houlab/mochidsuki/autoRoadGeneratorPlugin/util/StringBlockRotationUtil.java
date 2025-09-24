package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util;

import java.util.HashMap;
import java.util.Map;

/**
 * String版のブロック回転ユーティリティ（完全にスレッドセーフ）
 */
public class StringBlockRotationUtil {

    // 方向の回転マッピング（90度時計回り）
    private static final Map<String, String> FACING_ROTATION = new HashMap<>();
    private static final Map<String, String> AXIS_ROTATION = new HashMap<>();

    static {
        // facing回転マッピング
        FACING_ROTATION.put("north", "east");
        FACING_ROTATION.put("east", "south");
        FACING_ROTATION.put("south", "west");
        FACING_ROTATION.put("west", "north");
        FACING_ROTATION.put("up", "up");
        FACING_ROTATION.put("down", "down");

        // axis回転マッピング
        AXIS_ROTATION.put("x", "z");
        AXIS_ROTATION.put("z", "x");
        AXIS_ROTATION.put("y", "y");
    }

    /**
     * BlockDataStringを指定された角度で回転
     * @param blockDataString 元のBlockData文字列
     * @param rotationAngle 回転角度（ラジアン）
     * @return 回転後のBlockData文字列
     */
    public static String rotateBlockDataString(String blockDataString, double rotationAngle) {
        if (blockDataString == null || blockDataString.isEmpty()) {
            return blockDataString;
        }

        // 角度を90度単位に変換
        int quarterTurns = getQuarterTurns(rotationAngle);
        double degrees = Math.toDegrees(rotationAngle);

        // デバッグ: 詳細な回転情報（必要時のみ）
        boolean hasRotatableProperties = (blockDataString.contains("facing=") || blockDataString.contains("axis=") ||
                                        blockDataString.contains("north=") || blockDataString.contains("stairs"));
        if (quarterTurns > 0 && hasRotatableProperties) {
            // デバッグログは最小限に（エラー時以外は出力しない）
            // System.out.println("[DEBUG] Rotating: " + blockDataString + " by " + degrees + "°");
        }

        if (quarterTurns == 0) {
            return blockDataString;
        }

        String result = blockDataString;

        // quarterTurns回だけ回転を適用
        for (int i = 0; i < quarterTurns; i++) {
            String beforeRotation = result;
            result = rotateSingleStep(result);
            // デバッグログ（エラー検証時のみ有効化）
            // if (i == 0 && !beforeRotation.equals(result)) {
            //     System.out.println("[DEBUG] Step " + (i+1) + ": " + beforeRotation + " -> " + result);
            // }
        }

        return result;
    }

    /**
     * 一回の90度回転を適用
     */
    private static String rotateSingleStep(String blockDataString) {
        String result = blockDataString;

        // facingプロパティを回転
        result = rotateProperty(result, "facing");

        // axisプロパティを回転
        result = rotateAxisProperty(result, "axis");

        // 複数の方向プロパティがあるブロック（フェンスなど）
        result = rotateMultipleFacing(result);

        return result;
    }

    /**
     * 複数方向フェンスなどの回転処理
     */
    private static String rotateMultipleFacing(String blockDataString) {
        String result = blockDataString;

        // north/south/east/westの個別プロパティを回転
        if (result.contains("north=") || result.contains("south=") ||
            result.contains("east=") || result.contains("west=")) {

            // 各方向の値を保存
            String northValue = extractPropertyValue(result, "north");
            String eastValue = extractPropertyValue(result, "east");
            String southValue = extractPropertyValue(result, "south");
            String westValue = extractPropertyValue(result, "west");

            // 90度時計回り回転: north->east, east->south, south->west, west->north
            // 同時に全て置換（順序に依存しない）
            String tempResult = result;
            if (northValue != null) tempResult = tempResult.replaceAll("north=[^,\\]]*", "TEMP_EAST=" + northValue);
            if (eastValue != null) tempResult = tempResult.replaceAll("east=[^,\\]]*", "TEMP_SOUTH=" + eastValue);
            if (southValue != null) tempResult = tempResult.replaceAll("south=[^,\\]]*", "TEMP_WEST=" + southValue);
            if (westValue != null) tempResult = tempResult.replaceAll("west=[^,\\]]*", "TEMP_NORTH=" + westValue);

            // TEMPプレフィックスを削除
            result = tempResult
                    .replace("TEMP_NORTH=", "north=")
                    .replace("TEMP_EAST=", "east=")
                    .replace("TEMP_SOUTH=", "south=")
                    .replace("TEMP_WEST=", "west=");
        }

        return result;
    }

    /**
     * プロパティの値を抽出
     */
    private static String extractPropertyValue(String blockDataString, String property) {
        String pattern = property + "=([^,\\]]*)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(blockDataString);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * facingプロパティを回転
     */
    private static String rotateProperty(String blockDataString, String property) {
        for (Map.Entry<String, String> entry : FACING_ROTATION.entrySet()) {
            String oldValue = entry.getKey();
            String newValue = entry.getValue();
            String oldPattern = property + "=" + oldValue;
            String newPattern = property + "=" + newValue;

            if (blockDataString.contains(oldPattern)) {
                return blockDataString.replace(oldPattern, newPattern);
            }
        }
        return blockDataString;
    }

    /**
     * axisプロパティを回転
     */
    private static String rotateAxisProperty(String blockDataString, String property) {
        for (Map.Entry<String, String> entry : AXIS_ROTATION.entrySet()) {
            String oldValue = entry.getKey();
            String newValue = entry.getValue();
            String oldPattern = property + "=" + oldValue;
            String newPattern = property + "=" + newValue;

            if (blockDataString.contains(oldPattern)) {
                return blockDataString.replace(oldPattern, newPattern);
            }
        }
        return blockDataString;
    }

    /**
     * 角度から90度単位の回転回数を計算
     */
    private static int getQuarterTurns(double rotationAngle) {
        double degrees = Math.toDegrees(rotationAngle);
        degrees = ((degrees % 360) + 360) % 360; // 0-360度に正規化

        if (degrees >= 315 || degrees < 45) {
            return 0; // 0度
        } else if (degrees >= 45 && degrees < 135) {
            return 1; // 90度
        } else if (degrees >= 135 && degrees < 225) {
            return 2; // 180度
        } else {
            return 3; // 270度
        }
    }
}
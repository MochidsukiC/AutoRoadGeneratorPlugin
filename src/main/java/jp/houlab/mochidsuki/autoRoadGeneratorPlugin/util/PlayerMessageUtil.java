package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util;

import org.bukkit.entity.Player;
import java.lang.reflect.Method;

/**
 * プレイヤーへのメッセージ送信に関するユーティリティクラス
 * SpigotAPI環境でsendActionBarが利用できない場合の対応を含む
 */
public class PlayerMessageUtil {

    private static Method sendActionBarMethod = null;
    private static boolean methodChecked = false;

    static {
        // sendActionBarメソッドが存在するかチェック
        try {
            sendActionBarMethod = Player.class.getMethod("sendActionBar", String.class);
        } catch (NoSuchMethodException e) {
            // メソッドが存在しない場合はnullのまま
            sendActionBarMethod = null;
        }
        methodChecked = true;
    }

    /**
     * プレイヤーのアクションバーにメッセージを送信します。
     * sendActionBarが利用できない環境では、タイトルAPIを代替として使用します。
     * @param player 対象プレイヤー
     * @param message 送信するメッセージ
     */
    public static void sendActionBar(Player player, String message) {
        if (sendActionBarMethod != null) {
            try {
                sendActionBarMethod.invoke(player, message);
                return;
            } catch (Exception e) {
                // リフレクション実行に失敗した場合はフォールバックに進む
            }
        }

        // sendActionBarが利用できない場合はタイトルAPIを使用（サブタイトルとして表示）
        if (message != null && !message.isEmpty()) {
            player.sendTitle("", message, 0, 20, 10);
        }
    }

    /**
     * プレイヤーのアクションバーをクリアします。
     * sendActionBarが利用できない環境では、タイトルをクリアします。
     * @param player 対象プレイヤー
     */
    public static void clearActionBar(Player player) {
        if (sendActionBarMethod != null) {
            try {
                sendActionBarMethod.invoke(player, "");
                return;
            } catch (Exception e) {
                // リフレクション実行に失敗した場合はフォールバックに進む
            }
        }

        // sendActionBarが利用できない場合はタイトルをクリア
        player.sendTitle("", "", 0, 1, 0);
    }
}
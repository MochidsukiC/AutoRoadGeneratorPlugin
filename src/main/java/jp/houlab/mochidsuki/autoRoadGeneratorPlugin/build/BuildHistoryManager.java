package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BuildHistoryManager {
    // プレイヤーごとの建築履歴を保持するマップ。値のリストはスタックとして機能する。
    private static final Map<UUID, List<List<BlockPlacementInfo>>> buildHistory = new HashMap<>();

    /**
     * 新しい建築履歴をスタックに追加します。
     * @param uuid プレイヤーのUUID
     * @param blockList 元の状態に戻すためのブロック情報リスト
     */
    public static void addBuildHistory(UUID uuid, List<BlockPlacementInfo> blockList) {
        // 設置順の逆から元に戻すため、リストを反転させる
        Collections.reverse(blockList);
        // プレイヤーの履歴スタックがなければ初期化
        buildHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        // 履歴をスタックの末尾に追加 (push)
        buildHistory.get(uuid).add(blockList);
    }

    /**
     * 最後の建築操作を取り消し（アンドゥ）します。
     * @param uuid プレイヤーのUUID
     * @param plugin プラグインのメインインスタンス
     * @return アンドゥが成功した場合は true、履歴が空の場合は false
     */
    public static boolean undoLastBuild(UUID uuid, AutoRoadGeneratorPluginMain plugin) {
        List<List<BlockPlacementInfo>> playerHistory = buildHistory.get(uuid);

        // 履歴が存在しないか、空の場合は何もしない
        if (playerHistory == null || playerHistory.isEmpty()) {
            return false;
        }

        // スタックの最後の要素（＝最後の建築操作）を取得
        int lastIndex = playerHistory.size() - 1;
        List<BlockPlacementInfo> lastBuild = playerHistory.get(lastIndex);

        // 取得した履歴をワールドに戻すためのタスクを準備
        Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(lastBuild);
        new BuildPlacementTask(plugin, uuid, placementQueue).runTaskTimer(plugin, 1, 1);

        // 処理した履歴をスタックから削除 (pop)
        playerHistory.remove(lastIndex);

        return true;
    }
}

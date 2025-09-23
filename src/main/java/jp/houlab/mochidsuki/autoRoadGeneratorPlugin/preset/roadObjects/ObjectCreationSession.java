package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.roadObjects;

import org.bukkit.Location;

import java.util.UUID;

/**
 * オブジェクトプリセット作成中のプレイヤーの状態を管理するクラス。
 */
public class ObjectCreationSession {

    private final UUID playerUUID;
    private Location startLocation;
    private Location endLocation;
    private Location originLocation;

    public ObjectCreationSession(UUID playerUUID) {
        this.playerUUID = playerUUID;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public Location getStartLocation() {
        return startLocation;
    }

    public void setStartLocation(Location startLocation) {
        this.startLocation = startLocation;
    }

    public Location getEndLocation() {
        return endLocation;
    }

    public void setEndLocation(Location endLocation) {
        this.endLocation = endLocation;
    }

    public Location getOriginLocation() {
        return originLocation;
    }

    public void setOriginLocation(Location originLocation) {
        this.originLocation = originLocation;
    }

    /**
     * プリセット作成に必要な全ての点が設定されたかを確認します。
     * @return 全ての点が設定されていれば true
     */
    public boolean isReady() {
        return startLocation != null && endLocation != null && originLocation != null;
    }
}

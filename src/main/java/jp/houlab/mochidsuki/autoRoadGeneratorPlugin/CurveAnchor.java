package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Location;
import java.util.UUID;

/**
 * 道路の曲線エッジの形状を制御するアンカーポイントを表すクラスです。
 */
public class CurveAnchor {
    private final UUID id;
    private Location location;

    public CurveAnchor(Location location) {
        this.id = UUID.randomUUID();
        this.location = location;
    }

    public UUID getId() {
        return id;
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CurveAnchor that = (CurveAnchor) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

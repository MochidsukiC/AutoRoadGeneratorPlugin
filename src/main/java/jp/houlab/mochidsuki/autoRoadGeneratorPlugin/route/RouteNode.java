package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route;

import org.bukkit.Location;
import java.util.UUID;

/**
 * 道路の中継点（ノード）を表すクラスです。
 */
public class RouteNode {
    private final UUID id;
    private Location location;

    public RouteNode(Location location) {
        this.id = UUID.randomUUID();
        this.location = location;
    }

    public UUID getId() {
        return id;
    }

    public Location getLocation() {
        return location;
    }

    /**
     * ノードの座標を設定します。
     * @param location 新しい座標
     */
    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RouteNode routeNode = (RouteNode) o;
        return id.equals(routeNode.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

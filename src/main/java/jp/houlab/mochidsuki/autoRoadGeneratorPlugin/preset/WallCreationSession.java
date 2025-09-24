package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset;

import org.bukkit.Location;

public class WallCreationSession {
    private Location pos1;       // First corner of 3D wall volume
    private Location pos2;       // Second corner of 3D wall volume
    private Location axisStart;  // Start point of wall axis line
    private Location axisEnd;    // End point of wall axis line

    public Location getPos1() {
        return pos1;
    }

    public void setPos1(Location pos1) {
        this.pos1 = pos1;
    }

    public Location getPos2() {
        return pos2;
    }

    public void setPos2(Location pos2) {
        this.pos2 = pos2;
    }

    public Location getAxisStart() {
        return axisStart;
    }

    public void setAxisStart(Location axisStart) {
        this.axisStart = axisStart;
    }

    public Location getAxisEnd() {
        return axisEnd;
    }

    public void setAxisEnd(Location axisEnd) {
        this.axisEnd = axisEnd;
    }

    // Legacy compatibility
    @Deprecated
    public Location getAxis() {
        return axisStart;
    }

    @Deprecated
    public void setAxis(Location axis) {
        this.axisStart = axis;
    }
}
package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Location;

public class PresetCreationSession {
    private Location pos1;
    private Location pos2;
    private Location axisStart;
    private Location axisEnd;

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
}

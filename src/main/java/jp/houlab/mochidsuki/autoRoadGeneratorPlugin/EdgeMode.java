package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

public enum EdgeMode {
    STRAIGHT,
    CLOTHOID,
    ARC;

    private static final EdgeMode[] VALUES = values();

    public EdgeMode next() {
        return VALUES[(this.ordinal() + 1) % VALUES.length];
    }

    public EdgeMode previous() {
        return VALUES[(this.ordinal() - 1 + VALUES.length) % VALUES.length];
    }
}

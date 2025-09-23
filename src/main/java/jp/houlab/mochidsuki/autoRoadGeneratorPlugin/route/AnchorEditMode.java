package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route;

public enum AnchorEditMode {
    FREE,
    Y_AXIS_FIXED,
    Y_AXIS_ONLY;

    private static final AnchorEditMode[] VALUES = values();

    public AnchorEditMode next() {
        return VALUES[(this.ordinal() + 1) % VALUES.length];
    }

    public AnchorEditMode previous() {
        return VALUES[(this.ordinal() - 1 + VALUES.length) % VALUES.length];
    }
}

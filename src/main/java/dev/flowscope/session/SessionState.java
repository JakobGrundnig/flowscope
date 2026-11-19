package dev.flowscope.session;

/** Recording state shown in the lens screen. Ordinal is sent over the network; append only. */
public enum SessionState {
    RECORDING("recording"),
    STOPPED("stopped"),
    TIMEOUT("timeout"),
    OUT_OF_RANGE("out_of_range"),
    CHUNK_UNLOADED("chunk_unloaded"),
    TARGET_REMOVED("target_removed"),
    NO_INVENTORY("no_inventory"),
    TOO_LARGE("too_large"),
    ERROR("error"),
    DIMENSION_CHANGED("dimension_changed"),
    REPLACED("replaced"),
    SERVER_STOPPING("server_stopping"),
    /** Internal only: view closed or player gone; nothing is sent to the client. */
    CLOSED("closed");

    private static final SessionState[] VALUES = values();

    private final String key;

    SessionState(String key) {
        this.key = key;
    }

    public String translationKey() {
        return "flowscope.state." + key;
    }

    /** Short reason without the "Stopped:" prefix, for the action-bar message. */
    public String reasonKey() {
        return "flowscope.reason." + key;
    }

    public static SessionState byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : ERROR;
    }
}

package ehms.ws;

import ehms.db.Database;
import ehms.security.SessionManager;

/** Implemented by the optional Jetty-backed server (src/optional/java); discovered reflectively. */
public interface WebSocketProvider {

    /** @return the actual port the WebSocket server bound (0 = ephemeral). */
    int start(Database db, SessionManager sessions, int port, boolean secure) throws Exception;

    /** Sends a JSON payload to every subscriber of a room; no-op when the room is empty. */
    default void push(String room, String json) {}

    default void close() {}
}
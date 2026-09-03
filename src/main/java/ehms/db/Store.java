package ehms.db;

/**
 * Pluggable persistence. The whole application state lives in memory inside
 * {@link Database}; a Store knows how to load that state at startup and how
 * to persist it again after every change.
 *
 * Implementations: {@link FileStore} (default, ehms.dat) and
 * {@link JdbcStore} (SQLite or MySQL over plain JDBC).
 */
public interface Store {

    /** Fill the given (empty) database with the persisted state. */
    void load(Database db) throws Exception;

    /** Persist the full current state (called after every change). */
    void save(Database db) throws Exception;

    /** Write a timestamped backup; keep only the newest {@code keep} files. */
    void backup(String label, int keep) throws Exception;

    /** Release resources (called at shutdown). */
    void close();

    /** Human-readable description for the startup banner. */
    String describe();
}
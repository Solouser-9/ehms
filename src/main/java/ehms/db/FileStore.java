package ehms.db;

import ehms.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The default store: the whole Database object is Java-serialised to ehms.dat.
 * Writes go to a temporary file first and are then moved into place, so a
 * crash mid-write can never leave a half-written data file behind.
 * Old ehms.dat files from previous versions still load (missing fields
 * such as the audit trail are simply treated as empty).
 */
public final class FileStore implements Store {

    public static final String DATA_FILE = "ehms.dat";

    private final Path file;

    public FileStore() { this(DATA_FILE); }

    public FileStore(String fileName) { this.file = Path.of(fileName); }

    @Override
    public void load(Database db) {
        if (!Files.exists(file)) return;
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            Object read = in.readObject();
            if (read instanceof Database loaded) {
                db.copyStateFrom(loaded);
                db.applyCounters(null);      // self-heal the sequence counters
                Log.info("Database restored from " + file.toAbsolutePath());
            }
        } catch (Exception e) {
            Log.warn("Could not read " + file + " (" + e + "). Starting with an empty database.");
        }
    }

    @Override
    public void save(Database db) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeObject(db);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException plainMove) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void backup(String label, int keep) throws IOException {
        if (keep <= 0 || !Files.exists(file)) return;
        Files.createDirectories(Backups.BACKUP_DIR);
        Path target = Backups.BACKUP_DIR.resolve("ehms-" + label + ".dat");
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        Backups.prune(Backups.BACKUP_DIR, "ehms-", ".dat", keep);
    }

    @Override
    public void close() { /* nothing to close */ }

    @Override
    public String describe() { return "file " + file; }
}
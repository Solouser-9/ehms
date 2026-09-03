package ehms.db;

import ehms.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared helpers for timestamped, pruned backup files. */
public final class Backups {

    static final Path BACKUP_DIR = Path.of("backups");
    private static final Path UPLOADS = Path.of("uploads");

    private Backups() {}

    /** Timestamp label that also sorts chronologically when used as a file name. */
    public static String label(long millis) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(millis));
    }

    /** Keeps only the newest {@code keep} files starting with prefix and ending with suffix. */
    public static void prune(Path dir, String prefix, String suffix, int keep) {
        if (keep <= 0) return;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> matches = files
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.endsWith(suffix);
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = keep; i < matches.size(); i++) {
                try { Files.deleteIfExists(matches.get(i)); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /**
     * Zips uploads/ into backups/uploads-<label>.zip so patient report files
     * are part of the backup trail alongside the data backup (same timestamp
     * label pairs them). Pruned like every other backup. Written to a .tmp
     * file first so a crash can never leave a corrupt zip behind.
     */
    public static void backupUploads(String label, int keep) {
        if (keep <= 0 || !Files.isDirectory(UPLOADS)) return;
        try {
            Files.createDirectories(BACKUP_DIR);
            Path zip = BACKUP_DIR.resolve("uploads-" + label + ".zip");
            Path tmp = BACKUP_DIR.resolve("uploads-" + label + ".zip.tmp");
            Files.deleteIfExists(tmp);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                try (Stream<Path> files = Files.walk(UPLOADS)) {
                    files.filter(Files::isRegularFile).forEach(f -> {
                        try {
                            zos.putNextEntry(new ZipEntry(UPLOADS.relativize(f).toString()));
                            Files.copy(f, zos);
                            zos.closeEntry();
                        } catch (IOException skipped) {
                            // a file vanished or is unreadable mid-zip: skip it
                        }
                    });
                }
            }
            Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
            prune(BACKUP_DIR, "uploads-", ".zip", keep);
        } catch (IOException e) {
            Log.warn("Upload backup failed: " + e);
        }
    }
}
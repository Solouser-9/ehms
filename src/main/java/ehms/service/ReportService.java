package ehms.service;

import ehms.db.Database;
import ehms.model.Appointment;
import ehms.model.Attachment;
import ehms.security.SessionManager.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Patients upload medical reports; files live in uploads/ and are served with access checks. */
public class ReportService {

    public static final Path UPLOAD_DIR = Path.of("uploads");
    private static final long MAX_SIZE = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "pdf");

    public record Loaded(Attachment attachment, byte[] data) {}

    private final Database db;

    public ReportService(Database db) { this.db = db; }

    public Map<String, Object> upload(String patientId, String title, String fileName,
                                      String contentType, byte[] data) {
        if (data == null || data.length == 0)
            throw new IllegalArgumentException("Please choose a file to upload.");
        if (data.length > MAX_SIZE)
            throw new IllegalArgumentException("Files are limited to 5 MB.");
        String ext = ext(fileName);
        if (!ALLOWED.contains(ext))
            throw new IllegalArgumentException("Allowed file types: " + ALLOWED + " (got '." + ext + "').");
        if (title == null || title.trim().isEmpty()) title = baseName(fileName);

        synchronized (db) {
            // storedName carries the final id, so build the attachment in two steps.
            Attachment a = new Attachment(db.nextAttachmentId(), patientId, title.trim(),
                    fileName == null ? "report." + ext : fileName,
                    db.nextAttachmentIdPlaceholder(ext),
                    contentType == null || contentType.isBlank() ? guessType(ext) : contentType,
                    data.length, System.currentTimeMillis());
            a = new Attachment(a.getId(), a.getPatientId(), a.getTitle(), a.getFileName(),
                    a.getId() + "." + ext, a.getContentType(), a.getSize(), a.getUploadedAt());
            try {
                Files.createDirectories(UPLOAD_DIR);
                Files.write(UPLOAD_DIR.resolve(a.getStoredName()), data);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not store the file on the server: " + e.getMessage());
            }
            db.attachments.put(a.getId(), a);
            db.save();
            return a.toMap();
        }
    }

    public List<Map<String, Object>> mine(String patientId) {
        return listing(patientId);
    }

    /** A doctor may see the reports of a patient they have (or had) a consultation with. */
    public List<Map<String, Object>> forPatient(String doctorId, String patientId) {
        if (!sharesAppointment(doctorId, patientId))
            throw new IllegalArgumentException("You can only view reports of your own patients.");
        return listing(patientId);
    }

    public Map<String, Object> delete(String patientId, String attachmentId) {
        synchronized (db) {
            Attachment a = own(patientId, attachmentId);
            db.attachments.remove(a.getId());
            try { Files.deleteIfExists(UPLOAD_DIR.resolve(a.getStoredName())); } catch (IOException ignored) { }
            db.save();
            return a.toMap();
        }
    }

    public Loaded load(Session s, String attachmentId) {
        Attachment a = attachmentId == null ? null : db.attachments.get(attachmentId.trim());
        if (a == null) throw new IllegalArgumentException("Report not found: " + attachmentId);
        boolean allowed =
                ("PATIENT".equals(s.role()) && a.getPatientId().equals(s.accountId()))
                || "ADMIN".equals(s.role())
                || ("DOCTOR".equals(s.role()) && sharesAppointment(s.accountId(), a.getPatientId()));
        if (!allowed) throw new IllegalArgumentException("You do not have access to this report.");
        try {
            return new Loaded(a, Files.readAllBytes(UPLOAD_DIR.resolve(a.getStoredName())));
        } catch (IOException e) {
            throw new IllegalArgumentException("The report file is missing on the server (uploads/"
                    + a.getStoredName() + ").");
        }
    }

    private List<Map<String, Object>> listing(String patientId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attachment a : db.attachments.values())
            if (a.getPatientId().equals(patientId)) out.add(view(a));
        out.sort((x, y) -> Long.compare((Long) y.get("uploadedAt"), (Long) x.get("uploadedAt")));
        return out;
    }

    private boolean sharesAppointment(String doctorId, String patientId) {
        for (Appointment a : db.appointments.values()) {
            if (a.getDoctorId().equals(doctorId) && a.getPatientId().equals(patientId)) return true;
        }
        return false;
    }

    private Attachment own(String patientId, String attachmentId) {
        Attachment a = attachmentId == null ? null : db.attachments.get(attachmentId.trim());
        if (a == null || !a.getPatientId().equals(patientId))
            throw new IllegalArgumentException("Report not found: " + attachmentId);
        return a;
    }

    private static Map<String, Object> view(Attachment a) {
        Map<String, Object> m = a.toMap();
        m.put("url", "/api/file?id=" + a.getId());
        return m;
    }

    private static String ext(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String baseName(String fileName) {
        if (fileName == null) return "Medical report";
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static String guessType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }
}
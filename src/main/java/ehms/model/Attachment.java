package ehms.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** A medical report file uploaded by a patient (metadata; bytes live in uploads/). */
public class Attachment implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;              // R001
    private final String patientId;
    private final String title;
    private final String fileName;        // original file name
    private final String storedName;      // uploads/<id>.<ext>
    private final String contentType;
    private final long size;
    private final long uploadedAt;

    public Attachment(String id, String patientId, String title, String fileName,
                      String storedName, String contentType, long size, long uploadedAt) {
        this.id = id;
        this.patientId = patientId;
        this.title = title;
        this.fileName = fileName;
        this.storedName = storedName;
        this.contentType = contentType;
        this.size = size;
        this.uploadedAt = uploadedAt;
    }

    public String getId() { return id; }
    public String getPatientId() { return patientId; }
    public String getTitle() { return title; }
    public String getFileName() { return fileName; }
    public String getStoredName() { return storedName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public long getUploadedAt() { return uploadedAt; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("patientId", patientId);
        m.put("title", title);
        m.put("fileName", fileName);
        m.put("contentType", contentType);
        m.put("size", size);
        m.put("uploadedAt", uploadedAt);
        return m;
    }
}
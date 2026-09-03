package ehms.db;

import ehms.model.Admin;
import ehms.model.Appointment;
import ehms.model.Attachment;
import ehms.model.AuditEntry;
import ehms.model.Bed;
import ehms.model.BedRequest;
import ehms.model.Bill;
import ehms.model.Charge;
import ehms.model.ChatMessage;
import ehms.model.DailyOccupancy;
import ehms.model.Dispense;
import ehms.model.Doctor;
import ehms.model.Equipment;
import ehms.model.Hospital;
import ehms.model.Medicine;
import ehms.model.Patient;
import ehms.model.Payment;
import ehms.model.Slot;
import ehms.util.Json;
import ehms.util.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real-database persistence over plain JDBC - SQLite or MySQL.
 *
 *  - pooling via db.Pool (HikariCP when available, else the built-in fallback)
 *  - dirty-table sync: per-table content hashes mean unchanged tables skip ALL SQL
 *  - new tables (charges, daily_occupancy) and columns are added automatically
 *  - safe MySQL dumps (--safe-dump): CREATE TABLE IF NOT EXISTS + INSERT IGNORE
 */
public final class JdbcStore implements Store {

    private static final Path BACKUP_DIR = Path.of("backups");
    private static final int SQL_PARAM_CHUNK = 400;

    private static final String[] DOCTOR_COLS =
            {"id", "name", "email", "password_hash", "phone", "specialization", "license_no",
             "fee", "available", "verified", "blocked"};
    private static final String[] PATIENT_COLS =
            {"id", "name", "email", "password_hash", "phone", "age", "gender", "blood_group",
             "address", "blocked"};
    private static final String[] HOSPITAL_COLS =
            {"id", "name", "email", "password_hash", "phone", "address", "bed_counter",
             "bed_prices", "equipment_prices", "wards", "blocked"};
    private static final String[] BED_COLS =
            {"id", "hospital_id", "bed_no", "type", "ward", "patient_id", "admitted_at", "admitted_rate"};
    private static final String[] APPOINTMENT_COLS =
            {"id", "patient_id", "doctor_id", "symptoms", "status", "diagnosis", "prescription",
             "created_at", "completed_at", "slot_id", "scheduled_at", "fee"};
    private static final String[] SLOT_COLS =
            {"id", "doctor_id", "start_at", "duration_min", "status", "appointment_id"};
    private static final String[] MESSAGE_COLS =
            {"id", "appointment_id", "sender_role", "sender_name", "body", "sent_at", "read_at"};
    private static final String[] ATTACHMENT_COLS =
            {"id", "patient_id", "title", "file_name", "stored_name", "content_type", "size_bytes", "uploaded_at"};
    private static final String[] ADMIN_COLS =
            {"id", "name", "email", "password_hash", "blocked"};
    private static final String[] MEDICINE_COLS =
            {"id", "hospital_id", "name", "unit", "stock", "reorder_level", "price"};
    private static final String[] DISPENSE_COLS =
            {"id", "hospital_id", "medicine_id", "medicine_name", "patient_id", "qty", "unit",
             "dispensed_at", "note", "unit_price"};
    private static final String[] PAYMENT_COLS =
            {"id", "appointment_id", "patient_id", "patient_name", "doctor_id", "doctor_name",
             "amount", "status", "method", "created_at", "paid_at"};
    private static final String[] BED_REQUEST_COLS =
            {"id", "patient_id", "hospital_id", "bed_type", "reason", "status", "bed_id", "bed_no",
             "created_at", "decided_at", "decision_note"};
    private static final String[] BILL_COLS =
            {"id", "hospital_id", "patient_id", "patient_name", "bed_no", "bed_type", "ward",
             "admitted_at", "discharged_at", "days", "rate_per_day", "amount", "status", "method",
             "created_at", "paid_at", "lines"};
    private static final String[] EQUIPMENT_COLS =
            {"id", "hospital_id", "kind", "label", "status", "bed_id", "patient_id",
             "assigned_at", "created_at"};
    private static final String[] CHARGE_COLS =
            {"id", "hospital_id", "patient_id", "bed_id", "kind", "label", "qty", "unit_price",
             "amount", "created_at"};
    private static final String[] OCCUPANCY_COLS =
            {"id", "day", "hospital_id", "occupied", "total", "updated_at"};
    private static final String[] AUDIT_COLS =
            {"id", "ts", "actor_role", "actor_id", "actor_name", "actor_email", "action", "details"};
    private static final String[] COUNTER_COLS = {"name", "value"};

    private final String url;
    private final boolean mysql;
    private final Pool pool;
    private long auditHighWater;
    private boolean safeDump;
    private boolean schemaReady;
    private final Map<String, String> tableHashes = new HashMap<>();   // dirty-table detection

    public JdbcStore(String rawUrl) {
        String u = rawUrl == null ? "" : rawUrl.trim();
        if (u.startsWith("sqlite:") || u.startsWith("mysql:")) u = "jdbc:" + u;
        else if (!u.startsWith("jdbc:")) throw new IllegalArgumentException(
                "Unrecognised --db value '" + rawUrl + "'. Examples: sqlite:ehms.db  |  "
                + "mysql://localhost:3306/ehms?user=root&password=secret  |  a full jdbc: URL");
        this.url = u;
        this.mysql = u.startsWith("jdbc:mysql:");
        this.pool = Pool.create(u, this.mysql);   // HikariCP when available, else fallback
    }

    /** --safe-dump: MySQL backups restore into existing data without dropping tables. */
    public void setSafeDump(boolean safeDump) { this.safeDump = safeDump; }

    // ------------------------------------------------- schema

    /** Prepares tables/columns once, whichever pool is active. */
    private void ensureSchema() throws SQLException {
        if (schemaReady) return;
        Connection c = pool.borrow();
        try { prepareSchema(c); }
        finally { pool.giveBack(c); }
    }

    private void prepareSchema(Connection c) throws SQLException {
        if (schemaReady) return;
        try (Statement st = c.createStatement()) {
            for (String ddl : tableDdl().values()) st.execute(ddl);
        }
        ensureColumns(c);
        schemaReady = true;
    }

    private Map<String, String> tableDdl() {
        String idPk = mysql ? "VARCHAR(32) PRIMARY KEY" : "TEXT PRIMARY KEY";
        String idPk40 = mysql ? "VARCHAR(40) PRIMARY KEY" : "TEXT PRIMARY KEY";
        String s200 = mysql ? "VARCHAR(200)" : "TEXT";
        String s100 = mysql ? "VARCHAR(100)" : "TEXT";
        String s60  = mysql ? "VARCHAR(60)"  : "TEXT";
        String s50  = mysql ? "VARCHAR(50)"  : "TEXT";
        String s20  = mysql ? "VARCHAR(20)"  : "TEXT";
        String hash = mysql ? "VARCHAR(500)" : "TEXT";
        String s500 = mysql ? "VARCHAR(500)" : "TEXT";
        String ref  = mysql ? "VARCHAR(32)"  : "TEXT";

        Map<String, String> ddl = new LinkedHashMap<>();
        ddl.put("counters", "CREATE TABLE IF NOT EXISTS counters (name " + s50 + " PRIMARY KEY, value BIGINT NOT NULL)");
        ddl.put("doctors", "CREATE TABLE IF NOT EXISTS doctors (id " + idPk + ", name " + s200 + " NOT NULL, email "
                + s200 + " NOT NULL, password_hash " + hash + " NOT NULL, phone " + s50 + ", specialization " + s200
                + ", license_no " + s100 + ", fee DOUBLE NOT NULL, available BOOLEAN NOT NULL, verified BOOLEAN, blocked BOOLEAN)");
        ddl.put("patients", "CREATE TABLE IF NOT EXISTS patients (id " + idPk + ", name " + s200 + " NOT NULL, email "
                + s200 + " NOT NULL, password_hash " + hash + " NOT NULL, phone " + s50 + ", age INT NOT NULL, gender "
                + s20 + ", blood_group " + s20 + ", address " + s500 + ", blocked BOOLEAN)");
        ddl.put("hospitals", "CREATE TABLE IF NOT EXISTS hospitals (id " + idPk + ", name " + s200 + " NOT NULL, email "
                + s200 + " NOT NULL, password_hash " + hash + " NOT NULL, phone " + s50 + ", address " + s500
                + ", bed_counter INT NOT NULL, bed_prices " + s500 + ", equipment_prices " + s500 + ", wards "
                + s500 + ", blocked BOOLEAN)");
        ddl.put("beds", "CREATE TABLE IF NOT EXISTS beds (id " + idPk + ", hospital_id " + ref + " NOT NULL, bed_no INT NOT NULL, type "
                + s20 + " NOT NULL, ward " + s60 + ", patient_id " + ref + ", admitted_at BIGINT, admitted_rate DOUBLE)");
        ddl.put("appointments", "CREATE TABLE IF NOT EXISTS appointments (id " + idPk + ", patient_id " + ref
                + " NOT NULL, doctor_id " + ref + " NOT NULL, symptoms TEXT NOT NULL, status " + s20
                + " NOT NULL, diagnosis TEXT, prescription TEXT, created_at BIGINT NOT NULL, completed_at BIGINT, slot_id "
                + ref + ", scheduled_at BIGINT, fee DOUBLE)");
        ddl.put("slots", "CREATE TABLE IF NOT EXISTS slots (id " + idPk + ", doctor_id " + ref + " NOT NULL, start_at BIGINT NOT NULL, duration_min INT NOT NULL, status "
                + s20 + " NOT NULL, appointment_id " + ref + ")");
        ddl.put("messages", "CREATE TABLE IF NOT EXISTS messages (id " + idPk + ", appointment_id " + ref
                + " NOT NULL, sender_role " + s20 + " NOT NULL, sender_name " + s200 + ", body TEXT NOT NULL, sent_at BIGINT NOT NULL, read_at BIGINT)");
        ddl.put("attachments", "CREATE TABLE IF NOT EXISTS attachments (id " + idPk + ", patient_id " + ref
                + " NOT NULL, title " + s200 + " NOT NULL, file_name " + s200 + ", stored_name " + s100
                + " NOT NULL, content_type " + s100 + ", size_bytes BIGINT NOT NULL, uploaded_at BIGINT NOT NULL)");
        ddl.put("admins", "CREATE TABLE IF NOT EXISTS admins (id " + idPk + ", name " + s200 + " NOT NULL, email "
                + s200 + " NOT NULL, password_hash " + hash + " NOT NULL, blocked BOOLEAN)");
        ddl.put("medicines", "CREATE TABLE IF NOT EXISTS medicines (id " + idPk + ", hospital_id " + ref
                + " NOT NULL, name " + s200 + " NOT NULL, unit " + s50 + ", stock INT NOT NULL, reorder_level INT NOT NULL, price DOUBLE NOT NULL)");
        ddl.put("dispenses", "CREATE TABLE IF NOT EXISTS dispenses (id " + idPk + ", hospital_id " + ref
                + " NOT NULL, medicine_id " + ref + " NOT NULL, medicine_name " + s200 + " NOT NULL, patient_id " + ref
                + " NOT NULL, qty INT NOT NULL, unit " + s50 + ", dispensed_at BIGINT NOT NULL, note " + s500 + ", unit_price DOUBLE)");
        ddl.put("payments", "CREATE TABLE IF NOT EXISTS payments (id " + idPk + ", appointment_id " + ref
                + " NOT NULL, patient_id " + ref + " NOT NULL, patient_name " + s200 + " NOT NULL, doctor_id " + ref
                + " NOT NULL, doctor_name " + s200 + " NOT NULL, amount DOUBLE NOT NULL, status " + s20
                + " NOT NULL, method " + s20 + ", created_at BIGINT NOT NULL, paid_at BIGINT)");
        ddl.put("bed_requests", "CREATE TABLE IF NOT EXISTS bed_requests (id " + idPk + ", patient_id " + ref
                + " NOT NULL, hospital_id " + ref + " NOT NULL, bed_type " + s20 + " NOT NULL, reason " + s500
                + ", status " + s20 + " NOT NULL, bed_id " + ref + ", bed_no INT, created_at BIGINT NOT NULL, decided_at BIGINT, decision_note " + s500 + ")");
        ddl.put("bills", "CREATE TABLE IF NOT EXISTS bills (id " + idPk + ", hospital_id " + ref + " NOT NULL, patient_id "
                + ref + " NOT NULL, patient_name " + s200 + " NOT NULL, bed_no INT NOT NULL, bed_type " + s20
                + " NOT NULL, ward " + s60 + ", admitted_at BIGINT NOT NULL, discharged_at BIGINT NOT NULL, days INT NOT NULL, rate_per_day DOUBLE NOT NULL, amount DOUBLE NOT NULL, status "
                + s20 + " NOT NULL, method " + s20 + ", created_at BIGINT NOT NULL, paid_at BIGINT, lines TEXT)");
        ddl.put("equipment", "CREATE TABLE IF NOT EXISTS equipment (id " + idPk + ", hospital_id " + ref
                + " NOT NULL, kind " + s100 + " NOT NULL, label " + s200 + " NOT NULL, status " + s20
                + " NOT NULL, bed_id " + ref + ", patient_id " + ref + ", assigned_at BIGINT, created_at BIGINT NOT NULL)");
        ddl.put("charges", "CREATE TABLE IF NOT EXISTS charges (id " + idPk + ", hospital_id " + ref
                + " NOT NULL, patient_id " + ref + " NOT NULL, bed_id " + ref + ", kind " + s20
                + " NOT NULL, label " + s200 + " NOT NULL, qty DOUBLE NOT NULL, unit_price DOUBLE NOT NULL, amount DOUBLE NOT NULL, created_at BIGINT NOT NULL)");
        ddl.put("daily_occupancy", "CREATE TABLE IF NOT EXISTS daily_occupancy (id " + idPk40 + ", day " + s20
                + " NOT NULL, hospital_id " + ref + " NOT NULL, occupied INT NOT NULL, total INT NOT NULL, updated_at BIGINT NOT NULL)");
        ddl.put("audit_log", "CREATE TABLE IF NOT EXISTS audit_log (id " + idPk + ", ts BIGINT NOT NULL, actor_role "
                + s20 + ", actor_id " + ref + ", actor_name " + s200 + ", actor_email " + s200 + ", action " + s50
                + " NOT NULL, details TEXT)");
        return ddl;
    }

    /** Adds columns introduced by newer versions to databases created by older ones. */
    private void ensureColumns(Connection c) {
        ensureColumn(c, "doctors", "verified", "BOOLEAN");
        ensureColumn(c, "doctors", "blocked", "BOOLEAN");
        ensureColumn(c, "patients", "blocked", "BOOLEAN");
        ensureColumn(c, "hospitals", "blocked", "BOOLEAN");
        ensureColumn(c, "appointments", "slot_id", mysql ? "VARCHAR(32)" : "TEXT");
        ensureColumn(c, "appointments", "scheduled_at", "BIGINT");
        ensureColumn(c, "appointments", "fee", "DOUBLE");
        ensureColumn(c, "beds", "ward", mysql ? "VARCHAR(60)" : "TEXT");
        ensureColumn(c, "beds", "admitted_rate", "DOUBLE");
        ensureColumn(c, "hospitals", "bed_prices", "TEXT");
        ensureColumn(c, "hospitals", "equipment_prices", "TEXT");
        ensureColumn(c, "hospitals", "wards", "TEXT");
        ensureColumn(c, "dispenses", "unit_price", "DOUBLE");
        ensureColumn(c, "equipment", "patient_id", mysql ? "VARCHAR(32)" : "TEXT");
        ensureColumn(c, "equipment", "assigned_at", "BIGINT");
        ensureColumn(c, "bills", "lines", "TEXT");
    }

    private void ensureColumn(Connection c, String table, String column, String type) {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            Log.info("Added missing column " + table + "." + column);
        } catch (SQLException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (!(m.contains("duplicate") || m.contains("exists") || m.contains("already"))) {
                Log.warn("Could not ensure column " + table + "." + column + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------- load

    @Override
    public synchronized void load(Database db) throws SQLException {
        ensureSchema();
        Connection c = pool.borrow();
        try {
            loadAll(c, db);
        } finally {
            pool.giveBack(c);
        }
        // Seed the dirty-table hashes so even the first save writes only real changes.
        tableHashes.clear();
        for (Spec s : specs(db)) tableHashes.put(s.table(), tableHash(s.table(), s.rows(), s.liveIds()));
    }

    private void loadAll(Connection c, Database db) throws SQLException {
        db.doctors.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM doctors")) {
            while (rs.next()) {
                Doctor d = new Doctor(rs.getString("id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("phone"), rs.getString("specialization"),
                        rs.getString("license_no"), rs.getDouble("fee"));
                d.setAvailable(rs.getBoolean("available"));
                d.setVerifiedFlag(getNullableBool(rs, "verified"));
                d.setBlockedFlag(getNullableBool(rs, "blocked"));
                db.doctors.put(d.getId(), d);
            }
        }

        db.patients.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM patients")) {
            while (rs.next()) {
                Patient p = new Patient(rs.getString("id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("phone"), rs.getInt("age"),
                        rs.getString("gender"), rs.getString("blood_group"), rs.getString("address"));
                p.setBlockedFlag(getNullableBool(rs, "blocked"));
                db.patients.put(p.getId(), p);
            }
        }

        db.hospitals.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM hospitals")) {
            while (rs.next()) {
                Hospital h = new Hospital(rs.getString("id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("phone"), rs.getString("address"));
                h.setBedCounter(rs.getInt("bed_counter"));
                h.setBlockedFlag(getNullableBool(rs, "blocked"));
                h.setBedPrices(parsePrices(rs.getString("bed_prices")));
                h.setEquipmentPrices(parsePrices(rs.getString("equipment_prices")));
                parseWards(rs.getString("wards"), h);
                db.hospitals.put(h.getId(), h);
            }
        }

        db.admins.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM admins")) {
            while (rs.next()) {
                Admin a = new Admin(rs.getString("id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("password_hash"));
                a.setBlockedFlag(getNullableBool(rs, "blocked"));
                db.admins.put(a.getId(), a);
            }
        }

        db.beds.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM beds")) {
            while (rs.next()) {
                Bed b = new Bed(rs.getString("id"), rs.getString("hospital_id"),
                        rs.getInt("bed_no"), rs.getString("type"), rs.getString("ward"));
                String patientId = rs.getString("patient_id");
                if (patientId != null) {
                    long admittedAt = rs.getLong("admitted_at");
                    if (rs.wasNull()) admittedAt = 0;
                    b.admit(patientId, admittedAt, rs.getDouble("admitted_rate"));
                }
                db.beds.put(b.getId(), b);
            }
        }

        db.slots.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM slots")) {
            while (rs.next()) {
                Slot s = new Slot(rs.getString("id"), rs.getString("doctor_id"), rs.getLong("start_at"),
                        rs.getInt("duration_min"), rs.getString("status"), rs.getString("appointment_id"));
                db.slots.put(s.getId(), s);
            }
        }

        db.appointments.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM appointments")) {
            while (rs.next()) {
                long completedAt = rs.getLong("completed_at");
                if (rs.wasNull()) completedAt = 0;
                long scheduledAt = rs.getLong("scheduled_at");
                if (rs.wasNull()) scheduledAt = 0;
                Appointment a = new Appointment(rs.getString("id"), rs.getString("patient_id"),
                        rs.getString("doctor_id"), rs.getString("symptoms"), rs.getLong("created_at"),
                        rs.getString("slot_id"), scheduledAt, rs.getDouble("fee"), rs.getString("status"),
                        rs.getString("diagnosis"), rs.getString("prescription"), completedAt);
                db.appointments.put(a.getId(), a);
            }
        }

        db.messages.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM messages")) {
            while (rs.next()) {
                long readAt = rs.getLong("read_at");
                if (rs.wasNull()) readAt = 0;
                ChatMessage m = new ChatMessage(rs.getString("id"), rs.getString("appointment_id"),
                        rs.getString("sender_role"), rs.getString("sender_name"), rs.getString("body"),
                        rs.getLong("sent_at"), readAt);
                db.messages.put(m.getId(), m);
            }
        }

        db.attachments.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM attachments")) {
            while (rs.next()) {
                Attachment a = new Attachment(rs.getString("id"), rs.getString("patient_id"),
                        rs.getString("title"), rs.getString("file_name"), rs.getString("stored_name"),
                        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getLong("uploaded_at"));
                db.attachments.put(a.getId(), a);
            }
        }

        db.medicines.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM medicines")) {
            while (rs.next()) {
                Medicine m = new Medicine(rs.getString("id"), rs.getString("hospital_id"),
                        rs.getString("name"), rs.getString("unit"), rs.getInt("stock"),
                        rs.getInt("reorder_level"), rs.getDouble("price"));
                db.medicines.put(m.getId(), m);
            }
        }

        db.dispenses.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM dispenses")) {
            while (rs.next()) {
                Dispense d = new Dispense(rs.getString("id"), rs.getString("hospital_id"),
                        rs.getString("medicine_id"), rs.getString("medicine_name"), rs.getString("patient_id"),
                        rs.getInt("qty"), rs.getString("unit"), rs.getLong("dispensed_at"),
                        rs.getString("note"), rs.getDouble("unit_price"));
                db.dispenses.put(d.getId(), d);
            }
        }

        db.payments.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM payments")) {
            while (rs.next()) {
                long paidAt = rs.getLong("paid_at");
                if (rs.wasNull()) paidAt = 0;
                Payment pay = new Payment(rs.getString("id"), rs.getString("appointment_id"),
                        rs.getString("patient_id"), rs.getString("patient_name"), rs.getString("doctor_id"),
                        rs.getString("doctor_name"), rs.getDouble("amount"), rs.getLong("created_at"),
                        rs.getString("status"), rs.getString("method"), paidAt);
                db.payments.put(pay.getId(), pay);
            }
        }

        db.bedRequests.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM bed_requests")) {
            while (rs.next()) {
                long decidedAt = rs.getLong("decided_at");
                if (rs.wasNull()) decidedAt = 0;
                int bedNo = rs.getInt("bed_no");
                if (rs.wasNull()) bedNo = 0;
                BedRequest r = new BedRequest(rs.getString("id"), rs.getString("patient_id"),
                        rs.getString("hospital_id"), rs.getString("bed_type"), rs.getString("reason"),
                        rs.getLong("created_at"), rs.getString("status"), rs.getString("bed_id"), bedNo,
                        decidedAt, rs.getString("decision_note"));
                db.bedRequests.put(r.getId(), r);
            }
        }

        db.bills.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM bills")) {
            while (rs.next()) {
                long paidAt = rs.getLong("paid_at");
                if (rs.wasNull()) paidAt = 0;
                Bill bill = new Bill(rs.getString("id"), rs.getString("hospital_id"), rs.getString("patient_id"),
                        rs.getString("patient_name"), rs.getInt("bed_no"), rs.getString("bed_type"),
                        rs.getString("ward"), rs.getLong("admitted_at"), rs.getLong("discharged_at"),
                        rs.getInt("days"), rs.getDouble("rate_per_day"), rs.getDouble("amount"),
                        rs.getLong("created_at"), rs.getString("status"), rs.getString("method"), paidAt,
                        parseLines(rs.getString("lines")));
                db.bills.put(bill.getId(), bill);
            }
        }

        db.equipment.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM equipment")) {
            while (rs.next()) {
                long assignedAt = rs.getLong("assigned_at");
                if (rs.wasNull()) assignedAt = 0;
                Equipment e = new Equipment(rs.getString("id"), rs.getString("hospital_id"),
                        rs.getString("kind"), rs.getString("label"), rs.getLong("created_at"),
                        rs.getString("status"), rs.getString("bed_id"), rs.getString("patient_id"), assignedAt);
                db.equipment.put(e.getId(), e);
            }
        }

        db.charges.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM charges")) {
            while (rs.next()) {
                Charge ch = new Charge(rs.getString("id"), rs.getString("hospital_id"),
                        rs.getString("patient_id"), rs.getString("bed_id"), rs.getString("kind"),
                        rs.getString("label"), rs.getDouble("qty"), rs.getDouble("unit_price"),
                        rs.getDouble("amount"), rs.getLong("created_at"));
                db.charges.put(ch.getId(), ch);
            }
        }

        db.occupancy.clear();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM daily_occupancy")) {
            while (rs.next()) {
                DailyOccupancy o = new DailyOccupancy(rs.getString("id"), rs.getString("day"),
                        rs.getString("hospital_id"), rs.getInt("occupied"), rs.getInt("total"),
                        rs.getLong("updated_at"));
                db.occupancy.put(o.getId(), o);
            }
        }

        // audit: newest N into memory (table keeps everything); N respects --audit-cap
        int cap = db.getMaxAuditInMemory();
        int window = cap <= 0 ? 500 : Math.min(cap, 5000);
        List<AuditEntry> recent = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM audit_log ORDER BY ts DESC, id DESC LIMIT " + window)) {
            while (rs.next()) {
                recent.add(new AuditEntry(rs.getString("id"), rs.getLong("ts"), rs.getString("actor_role"),
                        rs.getString("actor_id"), rs.getString("actor_name"), rs.getString("actor_email"),
                        rs.getString("action"), rs.getString("details")));
            }
        }
        db.replaceAudit(recent);

        Map<String, Long> stored = new LinkedHashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name, value FROM counters")) {
            while (rs.next()) stored.put(rs.getString("name"), rs.getLong("value"));
        }
        db.applyCounters(stored);
        auditHighWater = stored.getOrDefault("audit", 0L);
        for (AuditEntry e : recent) auditHighWater = Math.max(auditHighWater, seqOf(e.getId()));

        Log.info("Loaded " + (db.doctors.size() + db.patients.size() + db.hospitals.size() + db.admins.size())
                + " accounts, " + db.beds.size() + " beds, " + db.appointments.size() + " appointments, "
                + db.bills.size() + " bills, " + db.charges.size() + " charges, "
                + db.occupancy.size() + " occupancy snapshots from " + describe());
    }

    // ------------------------------------------------- save (dirty-table sync)

    private record Spec(String table, String[] cols, List<Object[]> rows, Set<String> liveIds) {}

    private List<Spec> specs(Database db) {
        return List.of(
                new Spec("doctors", DOCTOR_COLS, doctorRows(db), db.doctors.keySet()),
                new Spec("patients", PATIENT_COLS, patientRows(db), db.patients.keySet()),
                new Spec("hospitals", HOSPITAL_COLS, hospitalRows(db), db.hospitals.keySet()),
                new Spec("admins", ADMIN_COLS, adminRows(db), db.admins.keySet()),
                new Spec("beds", BED_COLS, bedRows(db), db.beds.keySet()),
                new Spec("slots", SLOT_COLS, slotRows(db), db.slots.keySet()),
                new Spec("appointments", APPOINTMENT_COLS, appointmentRows(db), db.appointments.keySet()),
                new Spec("messages", MESSAGE_COLS, messageRows(db), db.messages.keySet()),
                new Spec("attachments", ATTACHMENT_COLS, attachmentRows(db), db.attachments.keySet()),
                new Spec("medicines", MEDICINE_COLS, medicineRows(db), db.medicines.keySet()),
                new Spec("dispenses", DISPENSE_COLS, dispenseRows(db), db.dispenses.keySet()),
                new Spec("payments", PAYMENT_COLS, paymentRows(db), db.payments.keySet()),
                new Spec("bed_requests", BED_REQUEST_COLS, bedRequestRows(db), db.bedRequests.keySet()),
                new Spec("bills", BILL_COLS, billRows(db), db.bills.keySet()),
                new Spec("equipment", EQUIPMENT_COLS, equipmentRows(db), db.equipment.keySet()),
                new Spec("charges", CHARGE_COLS, chargeRows(db), db.charges.keySet()),
                new Spec("daily_occupancy", OCCUPANCY_COLS, occupancyRows(db), db.occupancy.keySet()));
    }

    @Override
    public synchronized void save(Database db) throws SQLException {
        ensureSchema();
        Connection c = pool.borrow();
        Map<String, String> updated = new HashMap<>();
        boolean oldAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            for (Spec s : specs(db)) {
                String hash = tableHash(s.table(), s.rows(), s.liveIds());
                if (hash.equals(tableHashes.get(s.table()))) continue;   // unchanged -> no SQL at all
                syncTable(c, s.table(), s.cols(), s.rows(), s.liveIds());
                updated.put(s.table(), hash);
            }
            saveAudit(c, db);
            saveCounters(c, db);
            c.commit();
            tableHashes.putAll(updated);      // only after a successful commit
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(oldAutoCommit); } catch (SQLException ignored) { }
            pool.giveBack(c);
        }
    }

    /** Deterministic content hash over a table's rows (sorted by id) and live id set. */
    private static String tableHash(String table, List<Object[]> rows, Set<String> liveIds) {
        try {
            List<Object[]> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.comparing(r -> String.valueOf(r[0])));
            List<String> ids = new ArrayList<>(liveIds);
            Collections.sort(ids);
            StringBuilder sb = new StringBuilder(table).append('|');
            for (Object[] r : sorted)
                for (Object v : r) sb.append(v == null ? "\u0000" : String.valueOf(v)).append('\u0001');
            sb.append('|');
            for (String id : ids) sb.append(id).append('\u0002');
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(new byte[]{(byte) System.nanoTime()});  // never equal -> always sync
        }
    }

    // ---------------- row builders ----------------

    private static List<Object[]> doctorRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Doctor d : db.doctors.values()) {
            rows.add(new Object[]{ d.getId(), d.getName(), d.getEmail(), d.getPasswordHash(), d.getPhone(),
                    d.getSpecialization(), d.getLicenseNo(), d.getFee(), d.isAvailable(),
                    d.getVerifiedFlag(), d.getBlockedFlag() });
        }
        return rows;
    }

    private static List<Object[]> patientRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Patient p : db.patients.values()) {
            rows.add(new Object[]{ p.getId(), p.getName(), p.getEmail(), p.getPasswordHash(), p.getPhone(),
                    p.getAge(), p.getGender(), p.getBloodGroup(), p.getAddress(), p.getBlockedFlag() });
        }
        return rows;
    }

    private static List<Object[]> hospitalRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Hospital h : db.hospitals.values()) {
            rows.add(new Object[]{ h.getId(), h.getName(), h.getEmail(), h.getPasswordHash(), h.getPhone(),
                    h.getAddress(), h.getBedCounter(), Json.write(h.getBedPrices()),
                    Json.write(h.getEquipmentPrices()), wardsJson(h), h.getBlockedFlag() });
        }
        return rows;
    }

    private static String wardsJson(Hospital h) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Hospital.Ward w : h.getWards())
            m.put(w.getName(), Json.obj("floor", w.getFloor(), "capacity", w.getCapacity()));
        return Json.write(m);
    }

    private static List<Object[]> adminRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Admin a : db.admins.values()) {
            rows.add(new Object[]{ a.getId(), a.getName(), a.getEmail(), a.getPasswordHash(), a.getBlockedFlag() });
        }
        return rows;
    }

    private static List<Object[]> bedRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Bed b : db.beds.values()) {
            rows.add(new Object[]{ b.getId(), b.getHospitalId(), b.getBedNo(), b.getType(), b.getWard(),
                    b.getPatientId(), b.getAdmittedAt() == 0 ? null : b.getAdmittedAt(), b.getAdmittedRate() });
        }
        return rows;
    }

    private static List<Object[]> slotRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Slot s : db.slots.values()) {
            rows.add(new Object[]{ s.getId(), s.getDoctorId(), s.getStartAt(), s.getDurationMinutes(),
                    s.getStatus(), s.getAppointmentId() });
        }
        return rows;
    }

    private static List<Object[]> appointmentRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Appointment a : db.appointments.values()) {
            rows.add(new Object[]{ a.getId(), a.getPatientId(), a.getDoctorId(), a.getSymptoms(), a.getStatus(),
                    a.getDiagnosis(), a.getPrescription(), a.getCreatedAt(),
                    a.getCompletedAt() == 0 ? null : a.getCompletedAt(),
                    a.getSlotId(), a.getScheduledAt() == 0 ? null : a.getScheduledAt(), a.getFee() });
        }
        return rows;
    }

    private static List<Object[]> messageRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (ChatMessage m : db.messages.values()) {
            rows.add(new Object[]{ m.getId(), m.getAppointmentId(), m.getSenderRole(), m.getSenderName(),
                    m.getText(), m.getSentAt(), m.getReadAt() == 0 ? null : m.getReadAt() });
        }
        return rows;
    }

    private static List<Object[]> attachmentRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Attachment a : db.attachments.values()) {
            rows.add(new Object[]{ a.getId(), a.getPatientId(), a.getTitle(), a.getFileName(),
                    a.getStoredName(), a.getContentType(), a.getSize(), a.getUploadedAt() });
        }
        return rows;
    }

    private static List<Object[]> medicineRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Medicine m : db.medicines.values()) {
            rows.add(new Object[]{ m.getId(), m.getHospitalId(), m.getName(), m.getUnit(),
                    m.getStock(), m.getReorderLevel(), m.getPrice() });
        }
        return rows;
    }

    private static List<Object[]> dispenseRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Dispense d : db.dispenses.values()) {
            rows.add(new Object[]{ d.getId(), d.getHospitalId(), d.getMedicineId(), d.getMedicineName(),
                    d.getPatientId(), d.getQty(), d.getUnit(), d.getDispensedAt(), d.getNote(),
                    d.getUnitPrice() });
        }
        return rows;
    }

    private static List<Object[]> paymentRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Payment p : db.payments.values()) {
            rows.add(new Object[]{ p.getId(), p.getAppointmentId(), p.getPatientId(), p.getPatientName(),
                    p.getDoctorId(), p.getDoctorName(), p.getAmount(), p.getStatus(), p.getMethod(),
                    p.getCreatedAt(), p.getPaidAt() == 0 ? null : p.getPaidAt() });
        }
        return rows;
    }

    private static List<Object[]> bedRequestRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (BedRequest r : db.bedRequests.values()) {
            rows.add(new Object[]{ r.getId(), r.getPatientId(), r.getHospitalId(), r.getBedType(),
                    r.getReason(), r.getStatus(), r.getBedId(), r.getBedNo(), r.getCreatedAt(),
                    r.getDecidedAt() == 0 ? null : r.getDecidedAt(), r.getDecisionNote() });
        }
        return rows;
    }

    private static List<Object[]> billRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Bill b : db.bills.values()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Bill.Line l : b.getLines()) lines.add(l.toMap());
            rows.add(new Object[]{ b.getId(), b.getHospitalId(), b.getPatientId(), b.getPatientName(),
                    b.getBedNo(), b.getBedType(), b.getWard(), b.getAdmittedAt(), b.getDischargedAt(),
                    b.getDays(), b.getRatePerDay(), b.getAmount(), b.getStatus(), b.getMethod(),
                    b.getCreatedAt(), b.getPaidAt() == 0 ? null : b.getPaidAt(), Json.write(lines) });
        }
        return rows;
    }

    private static List<Object[]> equipmentRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Equipment e : db.equipment.values()) {
            rows.add(new Object[]{ e.getId(), e.getHospitalId(), e.getKind(), e.getLabel(),
                    e.getStatus(), e.getBedId(), e.getPatientId(),
                    e.getAssignedAt() == 0 ? null : e.getAssignedAt(), e.getCreatedAt() });
        }
        return rows;
    }

    private static List<Object[]> chargeRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (Charge c : db.charges.values()) {
            rows.add(new Object[]{ c.getId(), c.getHospitalId(), c.getPatientId(), c.getBedId(),
                    c.getKind(), c.getLabel(), c.getQty(), c.getUnitPrice(), c.getAmount(), c.getCreatedAt() });
        }
        return rows;
    }

    private static List<Object[]> occupancyRows(Database db) {
        List<Object[]> rows = new ArrayList<>();
        for (DailyOccupancy o : db.occupancy.values()) {
            rows.add(new Object[]{ o.getId(), o.getDay(), o.getHospitalId(), o.getOccupied(),
                    o.getTotal(), o.getUpdatedAt() });
        }
        return rows;
    }

    // ---------------- SQL plumbing ----------------

    private void syncTable(Connection c, String table, String[] cols, List<Object[]> rows, Set<String> liveIds)
            throws SQLException {
        if (!rows.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(upsertSql(table, cols, "id"))) {
                for (Object[] row : rows) {
                    bindAll(ps, row);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        deleteMissing(c, table, liveIds);
    }

    private String upsertSql(String table, String[] cols, String pkColumn) {
        String colList = String.join(", ", cols);
        String values = placeholders(cols.length);
        if (mysql) {
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (").append(colList)
                    .append(") VALUES (").append(values).append(") ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(cols[i]).append("=VALUES(").append(cols[i]).append(")");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (").append(colList)
                .append(") VALUES (").append(values).append(") ON CONFLICT(").append(pkColumn)
                .append(") DO UPDATE SET ");
        boolean first = true;
        for (String col : cols) {
            if (col.equals(pkColumn)) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(col).append("=excluded.").append(col);
        }
        return sb.toString();
    }

    private void deleteMissing(Connection c, String table, Set<String> liveIds) throws SQLException {
        if (liveIds.isEmpty()) {
            try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM " + table); }
            return;
        }
        List<String> ids = new ArrayList<>(liveIds);
        for (int start = 0; start < ids.size(); start += SQL_PARAM_CHUNK) {
            List<String> chunk = ids.subList(start, Math.min(start + SQL_PARAM_CHUNK, ids.size()));
            String sql = "DELETE FROM " + table + " WHERE id NOT IN (" + placeholders(chunk.size()) + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i));
                ps.executeUpdate();
            }
        }
    }

    private void saveAudit(Connection c, Database db) throws SQLException {
        long maxSeq = auditHighWater;
        List<AuditEntry> pending = new ArrayList<>();
        for (AuditEntry e : db.auditEntries()) {
            long seq = seqOf(e.getId());
            if (seq > auditHighWater) {
                pending.add(e);
                maxSeq = Math.max(maxSeq, seq);
            }
        }
        if (pending.isEmpty()) return;
        String sql = (mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ") + "audit_log ("
                + String.join(", ", AUDIT_COLS) + ") VALUES (" + placeholders(AUDIT_COLS.length) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (AuditEntry e : pending) {
                bindAll(ps, e.getId(), e.getTs(), e.getActorRole(), e.getActorId(), e.getActorName(),
                        e.getActorEmail(), e.getAction(), e.getDetails());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        auditHighWater = maxSeq;
    }

    private void saveCounters(Connection c, Database db) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(upsertSql("counters", COUNTER_COLS, "name"))) {
            for (Map.Entry<String, Long> e : db.countersMap().entrySet()) {
                bindAll(ps, e.getKey(), e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---------------- backup ----------------

    @Override
    public synchronized void backup(String label, int keep) {
        if (keep <= 0) return;
        try {
            ensureSchema();
            Files.createDirectories(BACKUP_DIR);
            if (mysql) {
                Connection c = pool.borrow();
                try { dumpSql(c, BACKUP_DIR.resolve("ehms-" + label + ".sql")); }
                finally { pool.giveBack(c); }
            } else {
                Path file = BACKUP_DIR.resolve("ehms-" + label + ".db");
                Files.deleteIfExists(file);
                try {
                    Connection c = pool.borrow();
                    try (Statement st = c.createStatement()) {
                        st.execute("VACUUM INTO '" + file.toAbsolutePath().toString().replace("'", "''") + "'");
                    } finally {
                        pool.giveBack(c);
                    }
                } catch (SQLException olderSqlite) {
                    Path dbFile = sqliteFile();
                    if (dbFile == null || !Files.exists(dbFile)) return;
                    Files.copy(dbFile, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Backups.prune(BACKUP_DIR, "ehms-", mysql ? ".sql" : ".db", keep);
        } catch (Exception e) {
            Log.warn("Backup failed: " + e);
        }
    }

    private void dumpSql(Connection c, Path file) throws SQLException, IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("-- E-HealthCare Management System - MySQL backup\n-- Generated " + new Date()
                    + "\n-- Mode: " + (safeDump ? "SAFE (no drops, INSERT IGNORE)" : "FULL (drops tables first)")
                    + "\nSET NAMES utf8mb4;\n");
            for (String table : tableDdl().keySet()) dumpTable(c, w, table);
        }
    }

    private void dumpTable(Connection c, BufferedWriter w, String table) throws SQLException, IOException {
        w.write("\n-- ---------- " + table + " ----------\n");
        if (safeDump) {
            w.write(tableDdl().get(table) + ";\n");          // CREATE TABLE IF NOT EXISTS ...
        } else {
            w.write("DROP TABLE IF EXISTS `" + table + "`;\n");
            w.write(tableDdl().get(table) + ";\n");
        }
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder cols = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                if (i > 1) cols.append(", ");
                cols.append(md.getColumnName(i));
            }
            while (rs.next()) {
                w.write((safeDump ? "INSERT IGNORE INTO " : "INSERT INTO ") + table + " (" + cols + ") VALUES (");
                for (int i = 1; i <= n; i++) {
                    if (i > 1) w.write(", ");
                    int type = md.getColumnType(i);
                    boolean textual = type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR
                            || type == Types.NCHAR || type == Types.NVARCHAR;
                    String v = rs.getString(i);
                    if (v == null) w.write("NULL");
                    else if (textual) w.write("'" + v.replace("\\", "\\\\").replace("'", "\\'")
                            .replace("\n", "\\n").replace("\r", "\\r") + "'");
                    else w.write(v);
                }
                w.write(");\n");
            }
        }
    }

    // ---------------- helpers ----------------

    private static Map<String, Double> parsePrices(String json) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Object parsed = Json.parse(json);
            if (parsed instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getValue() instanceof Number n)
                        out.put(String.valueOf(e.getKey()), n.doubleValue());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void parseWards(String json, Hospital h) {
        if (json == null || json.isBlank()) return;
        try {
            Object parsed = Json.parse(json);
            if (parsed instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) parsed).entrySet()) {
                    String floor = "";
                    int capacity = 0;
                    if (e.getValue() instanceof Map<?, ?> m) {
                        Object f = m.get("floor");
                        floor = f == null ? "" : String.valueOf(f);
                        if (m.get("capacity") instanceof Number n) capacity = n.intValue();
                    }
                    h.upsertWard(e.getKey(), floor, capacity);
                }
            }
        } catch (Exception ignored) { }
    }

    private static List<Bill.Line> parseLines(String json) {
        List<Bill.Line> lines = new ArrayList<>();
        if (json == null || json.isBlank()) return lines;
        try {
            Object parsed = Json.parse(json);
            if (parsed instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String kind = String.valueOf(m.get("kind"));
                        String label = String.valueOf(m.get("label"));
                        double qty = m.get("qty") instanceof Number n ? n.doubleValue() : 0;
                        double unitPrice = m.get("unitPrice") instanceof Number n ? n.doubleValue() : 0;
                        double amount = m.get("amount") instanceof Number n ? n.doubleValue() : 0;
                        lines.add(new Bill.Line(kind, label, qty, unitPrice, amount));
                    }
                }
            }
        } catch (Exception ignored) { }
        return lines;
    }

    private Path sqliteFile() {
        String prefix = "jdbc:sqlite:";
        if (!url.startsWith(prefix)) return null;
        String path = url.substring(prefix.length());
        if (path.isBlank() || ":memory:".equals(path)) return null;
        return Path.of(path);
    }

    private static long seqOf(String id) {
        try { return Long.parseLong(id.substring(1)); } catch (Exception e) { return 0; }
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private static void bindAll(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) bind(ps, i + 1, values[i]);
    }

    private static void bind(PreparedStatement ps, int index, Object v) throws SQLException {
        if (v == null) ps.setNull(index, Types.VARCHAR);
        else if (v instanceof Boolean b) ps.setBoolean(index, b);
        else if (v instanceof Integer n) ps.setInt(index, n);
        else if (v instanceof Long n) ps.setLong(index, n);
        else if (v instanceof Double n) ps.setDouble(index, n);
        else ps.setString(index, String.valueOf(v));
    }

    private static Boolean getNullableBool(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @Override
    public synchronized void close() {
        pool.closeAll();
    }

    @Override
    public String describe() {
        return (mysql ? "MySQL (" : "SQLite (") + url.replaceFirst("^jdbc:", "") + ")";
    }
}
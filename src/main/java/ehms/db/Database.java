package ehms.db;

import ehms.model.Account;
import ehms.model.Appointment;
import ehms.model.AuditEntry;
import ehms.model.Bed;
import ehms.model.BedRequest;
import ehms.model.Bill;
import ehms.model.Charge;
import ehms.model.DailyOccupancy;
import ehms.model.Doctor;
import ehms.model.Equipment;
import ehms.model.Hospital;
import ehms.model.Patient;
import ehms.util.Json;
import ehms.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Database {

    public static final long BACKUP_MIN_INTERVAL_MS = 60_000L;
    public static final int MAX_AUDIT_IN_MEMORY = 2000;
    public static final int OCCUPANCY_RETENTION_DAYS = 400;

    private static Database instance;

    private transient Store store;
    private transient int backupKeep = 10;
    private transient long lastBackupAt;
    private transient long backupMinIntervalMs = BACKUP_MIN_INTERVAL_MS;
    private transient int maxAuditInMemory = MAX_AUDIT_IN_MEMORY;

    public final Map<String, Doctor> doctors = new ConcurrentHashMap<>();
    public final Map<String, Patient> patients = new ConcurrentHashMap<>();
    public final Map<String, Hospital> hospitals = new ConcurrentHashMap<>();
    public final Map<String, Bed> beds = new ConcurrentHashMap<>();
    public final Map<String, Appointment> appointments = new ConcurrentHashMap<>();
    public final Map<String, ehms.model.Slot> slots = new ConcurrentHashMap<>();
    public final Map<String, ehms.model.ChatMessage> messages = new ConcurrentHashMap<>();
    public final Map<String, ehms.model.Attachment> attachments = new ConcurrentHashMap<>();
    public final Map<String, ehms.model.Admin> admins = new ConcurrentHashMap<>();
    public final Map<String, ehms.model.Medicine> medicines = new ConcurrentHashMap<>();
    public final Map<String, ehms.model.Dispense> dispenses = new ConcurrentHashMap<>();
    public final Map<String, ehms.model.Payment> payments = new ConcurrentHashMap<>();
    public final Map<String, BedRequest> bedRequests = new ConcurrentHashMap<>();
    public final Map<String, Bill> bills = new ConcurrentHashMap<>();
    public final Map<String, Equipment> equipment = new ConcurrentHashMap<>();
    public final Map<String, Charge> charges = new ConcurrentHashMap<>();
    public final Map<String, DailyOccupancy> occupancy = new ConcurrentHashMap<>();

    private LinkedList<AuditEntry> audit = new LinkedList<>();
    private int doctorSeq, patientSeq, hospitalSeq, bedSeq, appointmentSeq, auditSeq;
    private int slotSeq, messageSeq, attachmentSeq, adminSeq, medicineSeq, dispenseSeq, paymentSeq;
    private int bedRequestSeq, billSeq, equipmentSeq, chargeSeq;

//    private Database() {}

    public static synchronized Database getInstance() {
        if (instance == null) {
            instance = new Database();
            instance.store = new FileStore();
            try { instance.store.load(instance); }
            catch (Exception e) { Log.warn("Database load failed: " + e); }
        }
        return instance;
    }

//    public static synchronized Database configure(Store store) throws Exception {
//        if (instance != null) throw new IllegalStateException("Database is already initialised.");
//        instance = new Database();
//        instance.store = store;
//        store.load(instance);
//        return instance;
//    }

    public static synchronized Database configure(Store store) throws Exception {
        // If already initialised, just swap the store (don't re-load, don't crash)
        if (instance != null) {
            instance.store = store;
            return instance;
        }
        instance = new Database();
        instance.store = store;
        store.load(instance);
        return instance;
    }

    /** Used by BootConfig to attach a store without triggering a load. */
    public void setStore(Store s) {
        this.store = s;
    }

    public static synchronized Database createDetached() {
        return new Database();
    }

    public void setBackupKeep(int keep) { this.backupKeep = Math.max(0, keep); }
    public void setBackupMinIntervalMs(long ms) { this.backupMinIntervalMs = Math.max(0, ms); }
    public void setMaxAuditInMemory(int n) { this.maxAuditInMemory = n; }
    public int getMaxAuditInMemory() { return maxAuditInMemory; }
    public String storeDescription() { return store == null ? "none" : store.describe(); }

    public synchronized void save() {
        if (store == null) return;
        try {
            store.save(this);
        } catch (Exception e) {
            Log.error("Database save failed", e);
        }
        long now = System.currentTimeMillis();
        if (backupKeep > 0 && now - lastBackupAt >= backupMinIntervalMs) {
            lastBackupAt = now;
            String label = Backups.label(now);
            try { store.backup(label, backupKeep); }
            catch (Exception e) { Log.warn("Database backup failed: " + e); }
            Backups.backupUploads(label, backupKeep);
        }
    }

    public synchronized void backupNow() {
        if (store == null || backupKeep <= 0) return;
        String label = Backups.label(System.currentTimeMillis());
        try { store.backup(label, backupKeep); }
        catch (Exception e) { Log.warn("Database backup failed: " + e); }
        Backups.backupUploads(label, backupKeep);
    }

    public synchronized String nextAuditId() { return "L" + pad(++auditSeq); }

    public synchronized void recordAudit(AuditEntry entry) {
        LinkedList<AuditEntry> list = auditList();
        list.addFirst(entry);
        if (maxAuditInMemory > 0) while (list.size() > maxAuditInMemory) list.removeLast();
        save();
    }

    public synchronized List<AuditEntry> auditEntries() { return new ArrayList<>(auditList()); }

    synchronized void replaceAudit(List<AuditEntry> newestFirst) {
        audit = new LinkedList<>(newestFirst);
    }

    private LinkedList<AuditEntry> auditList() {
        if (audit == null) audit = new LinkedList<>();
        return audit;
    }

    public synchronized boolean snapshotOccupancy() {
        java.time.LocalDate today = java.time.LocalDate.now();
        String day = today.toString();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Hospital h : hospitals.values()) {
            int occupied = 0, total = 0;
            for (Bed b : beds.values()) {
                if (!b.getHospitalId().equals(h.getId())) continue;
                total++;
                if (!b.isFree()) occupied++;
            }
            String key = day + "|" + h.getId();
            DailyOccupancy existing = occupancy.get(key);
            if (existing == null || existing.getOccupied() != occupied || existing.getTotal() != total) {
                occupancy.put(key, new DailyOccupancy(key, day, h.getId(), occupied, total, now));
                changed = true;
            }
        }
        String cutoff = today.minusDays(OCCUPANCY_RETENTION_DAYS).toString();
        if (occupancy.values().removeIf(o -> o.getDay().compareTo(cutoff) < 0)) changed = true;
        return changed;
    }

    public synchronized String nextDoctorId()      { return "D" + pad(++doctorSeq); }
    public synchronized String nextPatientId()     { return "P" + pad(++patientSeq); }
    public synchronized String nextHospitalId()    { return "H" + pad(++hospitalSeq); }
    public synchronized String nextBedId()         { return "B" + pad(++bedSeq); }
    public synchronized String nextAppointmentId() { return "A" + pad(++appointmentSeq); }
    public synchronized String nextSlotId()        { return "S" + pad(++slotSeq); }
    public synchronized String nextMessageId()     { return "C" + pad(++messageSeq); }
    public synchronized String nextAttachmentId()  { return "R" + pad(++attachmentSeq); }
    public synchronized String nextAdminId()       { return "AD" + pad(++adminSeq); }
    public synchronized String nextMedicineId()    { return "MD" + pad(++medicineSeq); }
    public synchronized String nextDispenseId()    { return "DP" + pad(++dispenseSeq); }
    public synchronized String nextPaymentId()     { return "PY" + pad(++paymentSeq); }
    public synchronized String nextBedRequestId()  { return "BR" + pad(++bedRequestSeq); }
    public synchronized String nextBillId()        { return "BL" + pad(++billSeq); }
    public synchronized String nextEquipmentId()   { return "EQ" + pad(++equipmentSeq); }
    public synchronized String nextChargeId()      { return "CH" + pad(++chargeSeq); }

    public synchronized String nextAttachmentIdPlaceholder(String ext) { return "x." + ext; }

    private static String pad(int n) { return n < 1000 ? String.format("%03d", n) : String.valueOf(n); }

    public synchronized Map<String, Long> countersMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("doctor", (long) doctorSeq);
        m.put("patient", (long) patientSeq);
        m.put("hospital", (long) hospitalSeq);
        m.put("bed", (long) bedSeq);
        m.put("appointment", (long) appointmentSeq);
        m.put("audit", (long) auditSeq);
        m.put("slot", (long) slotSeq);
        m.put("message", (long) messageSeq);
        m.put("attachment", (long) attachmentSeq);
        m.put("admin", (long) adminSeq);
        m.put("medicine", (long) medicineSeq);
        m.put("dispense", (long) dispenseSeq);
        m.put("payment", (long) paymentSeq);
        m.put("bedRequest", (long) bedRequestSeq);
        m.put("bill", (long) billSeq);
        m.put("equipment", (long) equipmentSeq);
        m.put("charge", (long) chargeSeq);
        return m;
    }

    public synchronized void applyCounters(Map<String, Long> stored) {
        if (stored != null) {
            doctorSeq      = (int) Math.max(doctorSeq,      stored.getOrDefault("doctor", 0L));
            patientSeq     = (int) Math.max(patientSeq,     stored.getOrDefault("patient", 0L));
            hospitalSeq    = (int) Math.max(hospitalSeq,    stored.getOrDefault("hospital", 0L));
            bedSeq         = (int) Math.max(bedSeq,         stored.getOrDefault("bed", 0L));
            appointmentSeq = (int) Math.max(appointmentSeq, stored.getOrDefault("appointment", 0L));
            auditSeq       = (int) Math.max(auditSeq,       stored.getOrDefault("audit", 0L));
            slotSeq        = (int) Math.max(slotSeq,        stored.getOrDefault("slot", 0L));
            messageSeq     = (int) Math.max(messageSeq,     stored.getOrDefault("message", 0L));
            attachmentSeq  = (int) Math.max(attachmentSeq,  stored.getOrDefault("attachment", 0L));
            adminSeq       = (int) Math.max(adminSeq,       stored.getOrDefault("admin", 0L));
            medicineSeq    = (int) Math.max(medicineSeq,    stored.getOrDefault("medicine", 0L));
            dispenseSeq    = (int) Math.max(dispenseSeq,    stored.getOrDefault("dispense", 0L));
            paymentSeq     = (int) Math.max(paymentSeq,     stored.getOrDefault("payment", 0L));
            bedRequestSeq  = (int) Math.max(bedRequestSeq,  stored.getOrDefault("bedRequest", 0L));
            billSeq        = (int) Math.max(billSeq,        stored.getOrDefault("bill", 0L));
            equipmentSeq   = (int) Math.max(equipmentSeq,   stored.getOrDefault("equipment", 0L));
            chargeSeq      = (int) Math.max(chargeSeq,      stored.getOrDefault("charge", 0L));
        }
        doctorSeq      = Math.max(doctorSeq,      maxSeq(doctors.keySet(), "D"));
        patientSeq     = Math.max(patientSeq,     maxSeq(patients.keySet(), "P"));
        hospitalSeq    = Math.max(hospitalSeq,    maxSeq(hospitals.keySet(), "H"));
        bedSeq         = Math.max(bedSeq,         maxSeq(beds.keySet(), "B"));
        appointmentSeq = Math.max(appointmentSeq, maxSeq(appointments.keySet(), "A"));
        auditSeq       = Math.max(auditSeq,       maxSeq(auditIds(), "L"));
        slotSeq        = Math.max(slotSeq,        maxSeq(slots.keySet(), "S"));
        messageSeq     = Math.max(messageSeq,     maxSeq(messages.keySet(), "C"));
        attachmentSeq  = Math.max(attachmentSeq,  maxSeq(attachments.keySet(), "R"));
        adminSeq       = Math.max(adminSeq,       maxSeq(admins.keySet(), "AD"));
        medicineSeq    = Math.max(medicineSeq,    maxSeq(medicines.keySet(), "MD"));
        dispenseSeq    = Math.max(dispenseSeq,    maxSeq(dispenses.keySet(), "DP"));
        paymentSeq     = Math.max(paymentSeq,     maxSeq(payments.keySet(), "PY"));
        bedRequestSeq  = Math.max(bedRequestSeq,  maxSeq(bedRequests.keySet(), "BR"));
        billSeq        = Math.max(billSeq,        maxSeq(bills.keySet(), "BL"));
        equipmentSeq   = Math.max(equipmentSeq,   maxSeq(equipment.keySet(), "EQ"));
        chargeSeq      = Math.max(chargeSeq,      maxSeq(charges.keySet(), "CH"));
    }

    private List<String> auditIds() {
        List<String> ids = new ArrayList<>();
        for (AuditEntry e : auditList()) ids.add(e.getId());
        return ids;
    }

    private static int maxSeq(Collection<String> ids, String prefix) {
        int max = 0;
        if (ids == null) return max;
        for (String id : ids) {
            if (id == null || !id.startsWith(prefix)) continue;
            String rest = id.substring(prefix.length());
            if (rest.isEmpty()) continue;
            boolean allDigits = true;
            for (int i = 0; i < rest.length(); i++) {
                if (!Character.isDigit(rest.charAt(i))) { allDigits = false; break; }
            }
            if (allDigits) {
                try { max = Math.max(max, Integer.parseInt(rest)); } catch (NumberFormatException ignored) { }
            }
        }
        return max;
    }

    synchronized void copyStateFrom(Database other) {
        doctors.clear();      if (other.doctors != null) doctors.putAll(other.doctors);
        patients.clear();     if (other.patients != null) patients.putAll(other.patients);
        hospitals.clear();    if (other.hospitals != null) hospitals.putAll(other.hospitals);
        beds.clear();         if (other.beds != null) beds.putAll(other.beds);
        appointments.clear(); if (other.appointments != null) appointments.putAll(other.appointments);
        slots.clear();        if (other.slots != null) slots.putAll(other.slots);
        messages.clear();     if (other.messages != null) messages.putAll(other.messages);
        attachments.clear();  if (other.attachments != null) attachments.putAll(other.attachments);
        admins.clear();       if (other.admins != null) admins.putAll(other.admins);
        medicines.clear();    if (other.medicines != null) medicines.putAll(other.medicines);
        dispenses.clear();    if (other.dispenses != null) dispenses.putAll(other.dispenses);
        payments.clear();     if (other.payments != null) payments.putAll(other.payments);
        bedRequests.clear();  if (other.bedRequests != null) bedRequests.putAll(other.bedRequests);
        bills.clear();        if (other.bills != null) bills.putAll(other.bills);
        equipment.clear();    if (other.equipment != null) equipment.putAll(other.equipment);
        charges.clear();      if (other.charges != null) charges.putAll(other.charges);
        occupancy.clear();    if (other.occupancy != null) occupancy.putAll(other.occupancy);
        doctorSeq = other.doctorSeq;
        patientSeq = other.patientSeq;
        hospitalSeq = other.hospitalSeq;
        bedSeq = other.bedSeq;
        appointmentSeq = other.appointmentSeq;
        auditSeq = other.auditSeq;
        slotSeq = other.slotSeq;
        messageSeq = other.messageSeq;
        attachmentSeq = other.attachmentSeq;
        adminSeq = other.adminSeq;
        medicineSeq = other.medicineSeq;
        dispenseSeq = other.dispenseSeq;
        paymentSeq = other.paymentSeq;
        bedRequestSeq = other.bedRequestSeq;
        billSeq = other.billSeq;
        equipmentSeq = other.equipmentSeq;
        chargeSeq = other.chargeSeq;
        audit = (other.audit == null) ? new LinkedList<>() : new LinkedList<>(other.audit);
    }

    public <T extends Account> T byEmail(Map<String, ? extends T> table, String email) {
        if (email == null) return null;
        String needle = email.trim();
        for (T t : table.values()) {
            if (t.getEmail().equalsIgnoreCase(needle)) return t;
        }
        return null;
    }

    public synchronized Map<String, Object> stats() {
        int freeBeds = 0, occupiedBeds = 0, pending = 0, completed = 0;
        for (Bed b : beds.values()) if (b.isFree()) freeBeds++; else occupiedBeds++;
        for (Appointment a : appointments.values()) if (a.isCompleted()) completed++; else pending++;
        return Json.obj(
                "doctors", doctors.size(),
                "patients", patients.size(),
                "hospitals", hospitals.size(),
                "totalBeds", beds.size(),
                "freeBeds", freeBeds,
                "occupiedBeds", occupiedBeds,
                "pendingConsultations", pending,
                "completedConsultations", completed,
                "auditEvents", auditList().size());
    }

    public synchronized List<Map<String, Object>> dailyStats(int days) {
        int window = Math.max(1, Math.min(days, 90));
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate today = java.time.LocalDate.now(zone);

        List<long[]> stays = new ArrayList<>();
        for (Bill b : bills.values()) stays.add(new long[]{b.getAdmittedAt(), b.getDischargedAt()});
        for (Bed bed : beds.values())
            if (!bed.isFree()) stays.add(new long[]{bed.getAdmittedAt(), Long.MAX_VALUE});

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = window - 1; i >= 0; i--) {
            java.time.LocalDate day = today.minusDays(i);
            long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = start + 86_400_000L;
            long snapshot = Math.min(end, System.currentTimeMillis());
            int booked = 0, completed = 0;
            for (Appointment a : appointments.values()) {
                if (a.getCreatedAt() >= start && a.getCreatedAt() < end) booked++;
                if (a.isCompleted() && a.getCompletedAt() >= start && a.getCompletedAt() < end) completed++;
            }
            // NOTE: local variable is "occ" - the old name "occupancy" shadowed the
            // Map<String, DailyOccupancy> field, breaking occupancy.values() lookups.
            int occ;
            if (i == 0) {
                occ = 0;
                for (Bed bed : beds.values()) if (!bed.isFree()) occ++;
            } else {
                Integer snapSum = null;
                String dayStr = day.toString();
                for (DailyOccupancy o : occupancy.values()) {
                    if (o.getDay().equals(dayStr)) {
                        if (snapSum == null) snapSum = 0;
                        snapSum += o.getOccupied();
                    }
                }
                if (snapSum != null) occ = snapSum;
                else {
                    occ = 0;
                    for (long[] s : stays) if (s[0] <= snapshot && s[1] > snapshot) occ++;
                }
            }
            out.add(Json.obj("date", day.toString(),
                    "booked", booked, "completed", completed, "occupancy", occ));
        }
        return out;
    }
}
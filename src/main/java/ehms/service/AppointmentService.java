package ehms.service;

import ehms.db.Database;
import ehms.model.Appointment;
import ehms.model.Doctor;
import ehms.model.Patient;
import ehms.model.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class AppointmentService {

    private final Database db;

    public AppointmentService(Database db) { this.db = db; }

    /** Books a consultation: the patient must pick one of the doctor's open time slots. */
    public Map<String, Object> book(String patientId, String doctorId, String slotId, String symptoms) {
        Patient p = patient(patientId);
        Doctor d = doctor(doctorId);
        if (!d.isVerified())
            throw new IllegalArgumentException("Dr. " + d.getName()
                    + "'s licence verification is pending. Please choose a verified doctor.");
        if (d.isBlocked())
            throw new IllegalArgumentException("Dr. " + d.getName() + " is currently unavailable.");
        if (!d.isAvailable())
            throw new IllegalArgumentException("Dr. " + d.getName() + " is not accepting consultations right now.");
        String sym = Validation.require(symptoms, "Please describe your symptoms");

        synchronized (db) {
            Slot slot = slotId == null ? null : db.slots.get(slotId.trim());
            if (slot == null || !slot.getDoctorId().equals(d.getId()))
                throw new IllegalArgumentException("Please select one of the doctor's open time slots.");
            if (!slot.bookable(System.currentTimeMillis()))
                throw new IllegalArgumentException("That time slot is no longer available. Please pick another one.");

            // The doctor's fee is SNAPSHOTTED at booking time - later fee changes
            // never affect this consultation's due payment.
            Appointment a = new Appointment(db.nextAppointmentId(), p.getId(), d.getId(),
                    sym.trim(), System.currentTimeMillis(), slot.getId(), slot.getStartAt(), d.getFee());
            slot.book(a.getId());
            db.appointments.put(a.getId(), a);
            db.save();
            return display(a);
        }
    }

    /** The patient cancels a pending consultation; the slot is released again. */
    public Map<String, Object> cancel(String patientId, String appointmentId) {
        synchronized (db) {
            Appointment a = appointment(appointmentId);
            if (!a.getPatientId().equals(patientId))
                throw new IllegalArgumentException("This consultation belongs to a different patient.");
            if (!Appointment.PENDING.equals(a.getStatus()))
                throw new IllegalArgumentException("Only pending consultations can be cancelled.");
            a.cancel();
            if (a.getSlotId() != null) {
                Slot slot = db.slots.get(a.getSlotId());
                if (slot != null && a.getId().equals(slot.getAppointmentId())) slot.release();
            }
            db.save();
            return display(a);
        }
    }

    public List<Map<String, Object>> forPatient(String patientId, String statusFilter) {
        patient(patientId);
        String id = patientId.trim();
        return collect(a -> a.getPatientId().equals(id) && matches(a, statusFilter));
    }

    public List<Map<String, Object>> forDoctor(String doctorId, String statusFilter) {
        doctor(doctorId);
        String id = doctorId.trim();
        return collect(a -> a.getDoctorId().equals(id) && matches(a, statusFilter));
    }

    /** The doctor closes a consultation with a diagnosis and prescription. */
    public Map<String, Object> consult(String doctorId, String appointmentId,
                                       String diagnosis, String prescription) {
        doctor(doctorId);
        synchronized (db) {
            Appointment a = appointment(appointmentId);
            if (!a.getDoctorId().equals(doctorId.trim()))
                throw new IllegalArgumentException("This consultation belongs to a different doctor.");
            if (a.isCompleted()) throw new IllegalArgumentException("This consultation is already completed.");
            if (Appointment.CANCELLED.equals(a.getStatus()))
                throw new IllegalArgumentException("This consultation was cancelled by the patient.");
            String diag = Validation.require(diagnosis, "Diagnosis");
            String rx = Validation.require(prescription, "Prescription");
            a.complete(diag.trim(), rx.trim());
            db.save();
            return display(a);
        }
    }

    private boolean matches(Appointment a, String statusFilter) {
        return statusFilter == null || statusFilter.isBlank()
                || a.getStatus().equalsIgnoreCase(statusFilter.trim());
    }

    private List<Map<String, Object>> collect(Predicate<Appointment> filter) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Appointment a : db.appointments.values()) if (filter.test(a)) out.add(display(a));
        out.sort((x, y) -> Long.compare(((Number) y.get("createdAt")).longValue(),
                                        ((Number) x.get("createdAt")).longValue()));
        return out;
    }

    private Map<String, Object> display(Appointment a) {
        Patient p = db.patients.get(a.getPatientId());
        Doctor d = db.doctors.get(a.getDoctorId());
        Map<String, Object> m = a.toMap();
        m.put("patientName", p == null ? "?" : p.getName());
        m.put("patientAge", p == null ? null : p.getAge());
        m.put("patientPhone", p == null ? null : p.getPhone());
        m.put("doctorName", d == null ? "?" : d.getName());
        m.put("specialization", d == null ? null : d.getSpecialization());
        return m;
    }

    private Patient patient(String id) {
        Patient p = id == null ? null : db.patients.get(id.trim());
        if (p == null) throw new IllegalArgumentException("Patient not found: " + id);
        return p;
    }

    private Doctor doctor(String id) {
        Doctor d = id == null ? null : db.doctors.get(id.trim());
        if (d == null) throw new IllegalArgumentException("Doctor not found: " + id);
        return d;
    }

    private Appointment appointment(String id) {
        Appointment a = id == null ? null : db.appointments.get(id.trim());
        if (a == null) throw new IllegalArgumentException("Appointment not found: " + id);
        return a;
    }
}
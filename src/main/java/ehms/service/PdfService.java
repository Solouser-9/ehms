package ehms.service;

import ehms.db.Database;
import ehms.model.Appointment;
import ehms.model.Bill;
import ehms.model.Doctor;
import ehms.model.Hospital;
import ehms.model.Patient;
import ehms.security.SessionManager.Session;
import ehms.util.PdfFactory;
import ehms.util.PdfWriter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Generates the prescription, history and hospital-bill PDFs (Unicode via PDFBox when available). */
public class PdfService {

    private static final double M = 56;            // page margin
    private static final int CHARS_PER_LINE = 88;  // ~10pt Helvetica on A4 with 56pt margins

    private final Database db;

    public PdfService(Database db) { this.db = db; }

    public byte[] prescription(Session s, String appointmentId) {
        Appointment a = appointment(appointmentId);
        requireAccess(s, a);
        if (!a.isCompleted())
            throw new IllegalArgumentException("A PDF prescription exists only after the consultation is completed.");
        Patient p = db.patients.get(a.getPatientId());
        Doctor d = db.doctors.get(a.getDoctorId());

        PdfWriter pdf = PdfFactory.create();
        pdf.text(M, PdfWriter.PAGE_HEIGHT - 56, 16, true, "E-HealthCare Management System");
        pdf.text(M, PdfWriter.PAGE_HEIGHT - 74, 10, false, "Digital Medical Prescription");
        pdf.line(M, PdfWriter.PAGE_HEIGHT - 86, PdfWriter.PAGE_WIDTH - M, PdfWriter.PAGE_HEIGHT - 86, 1);

        double y = PdfWriter.PAGE_HEIGHT - 112;
        y = row(pdf, y, "Consultation ID", a.getId());
        y = row(pdf, y, "Patient", p == null ? "?" : p.getName() + "  (age " + p.getAge() + ")");
        y = row(pdf, y, "Blood group", p == null || p.getBloodGroup() == null || p.getBloodGroup().isEmpty() ? "-" : p.getBloodGroup());
        y = row(pdf, y, "Doctor", d == null ? "?" : "Dr. " + d.getName() + "  (" + d.getSpecialization() + ")");
        if (a.getScheduledAt() > 0) y = row(pdf, y, "Scheduled slot", dt(a.getScheduledAt()));
        y = row(pdf, y, "Issued on", dt(a.getCompletedAt()));

        pdf.line(M, y, PdfWriter.PAGE_WIDTH - M, y, 0.8);
        y -= 26;
        y = para(pdf, y, "Symptoms", a.getSymptoms());
        y -= 8;
        y = para(pdf, y, "Diagnosis", a.getDiagnosis());
        y -= 8;
        y = para(pdf, y, "Prescription", a.getPrescription());

        pdf.line(M, 116, PdfWriter.PAGE_WIDTH - M, 116, 0.8);
        pdf.text(PdfWriter.PAGE_WIDTH - M - 178, 98, 10, false, "Signature: ______________________");
        return pdf.bytes();
    }

    public byte[] history(Session s) {
        if (!"PATIENT".equals(s.role()))
            throw new IllegalArgumentException("Prescription history is available to patients only.");
        Patient p = db.patients.get(s.accountId());
        List<Appointment> done = new ArrayList<>();
        for (Appointment a : db.appointments.values())
            if (a.getPatientId().equals(s.accountId()) && a.isCompleted()) done.add(a);
        done.sort((x, y) -> Long.compare(y.getCompletedAt(), x.getCompletedAt()));
        if (done.isEmpty())
            throw new IllegalArgumentException("You have no completed consultations yet.");

        PdfWriter pdf = PdfFactory.create();
        pdf.text(M, PdfWriter.PAGE_HEIGHT - 56, 16, true, "E-HealthCare Management System");
        pdf.text(M, PdfWriter.PAGE_HEIGHT - 74, 10, false, "Complete Prescription History - " + p.getName());
        pdf.line(M, PdfWriter.PAGE_HEIGHT - 86, PdfWriter.PAGE_WIDTH - M, PdfWriter.PAGE_HEIGHT - 86, 1);
        double y = PdfWriter.PAGE_HEIGHT - 116;

        for (Appointment a : done) {
            if (y < 210) { pdf.newPage(); y = PdfWriter.PAGE_HEIGHT - 56; }
            Doctor d = db.doctors.get(a.getDoctorId());
            pdf.text(M, y, 12, true, "Consultation " + a.getId() + "  -  " + dt(a.getCompletedAt()));
            y -= 20;
            y = row(pdf, y, "Doctor", d == null ? "?" : "Dr. " + d.getName() + "  (" + d.getSpecialization() + ")");
            y = para(pdf, y, "Symptoms", a.getSymptoms());
            y = para(pdf, y, "Diagnosis", a.getDiagnosis());
            y = para(pdf, y, "Prescription", a.getPrescription());
            y -= 8;
            pdf.line(M, y, PdfWriter.PAGE_WIDTH - M, y, 0.6);
            y -= 26;
        }
        return pdf.bytes();
    }

    public byte[] bill(Session s, String billId) { return bill(s, billId, "Rs."); }

    /** The itemised hospital bill as a PDF (patient, owning hospital or admin only). */
    public byte[] bill(Session s, String billId, String currency) {
        Bill b = billId == null ? null : db.bills.get(billId.trim());
        if (b == null) throw new IllegalArgumentException("Bill not found: " + billId);
        boolean allowed = ("PATIENT".equals(s.role()) && b.getPatientId().equals(s.accountId()))
                || ("HOSPITAL".equals(s.role()) && b.getHospitalId().equals(s.accountId()))
                || "ADMIN".equals(s.role());
        if (!allowed) throw new IllegalArgumentException("You do not have access to this bill.");
        Hospital h = db.hospitals.get(b.getHospitalId());

        PdfWriter pdf = PdfFactory.create();
        pdf.text(M, PdfWriter.PAGE_HEIGHT - 56, 16, true, "E-HealthCare Management System");
        pdf.text(M, PdfWriter.PAGE_HEIGHT - 74, 10, false, "Hospital Bill / Invoice");
        pdf.line(M, PdfWriter.PAGE_HEIGHT - 86, PdfWriter.PAGE_WIDTH - M, PdfWriter.PAGE_HEIGHT - 86, 1);

        double y = PdfWriter.PAGE_HEIGHT - 112;
        y = row(pdf, y, "Bill ID", b.getId());
        y = row(pdf, y, "Hospital", h == null ? "?" : h.getName());
        y = row(pdf, y, "Patient", b.getPatientName());
        y = row(pdf, y, "Bed", "Bed " + b.getBedNo() + " (" + b.getBedType()
                + (b.getWard() == null || b.getWard().isEmpty() ? "" : ", " + b.getWard()) + ")");
        y = row(pdf, y, "Admitted", dt(b.getAdmittedAt()));
        y = row(pdf, y, "Discharged", dt(b.getDischargedAt()));
        y = row(pdf, y, "Billable days", String.valueOf(b.getDays()));
        y = row(pdf, y, "Status", b.isPaid() ? "PAID (" + b.getMethod() + ")" : "DUE");

        pdf.line(M, y, PdfWriter.PAGE_WIDTH - M, y, 0.8);
        y -= 26;
        pdf.text(M, y, 11, true, "Charges");
        y -= 16;
        for (Bill.Line line : b.getLines()) {
            if (y < 150) { pdf.newPage(); y = PdfWriter.PAGE_HEIGHT - 56; }
            List<String> wrapped = wrap(line.getLabel());
            for (int i = 0; i < wrapped.size(); i++) {
                pdf.text(M, y, 10, false, wrapped.get(i));
                if (i == 0) pdf.text(PdfWriter.PAGE_WIDTH - M - 100, y, 10, false, currency + " " + amount(line.getAmount()));
                y -= 13;
            }
            y -= 3;
        }
        pdf.line(M, y, PdfWriter.PAGE_WIDTH - M, y, 0.8);
        y -= 20;
        pdf.text(M, y, 12, true, "TOTAL");
        pdf.text(PdfWriter.PAGE_WIDTH - M - 120, y, 12, true, currency + " " + amount(b.getAmount()));
        pdf.line(M, 116, PdfWriter.PAGE_WIDTH - M, 116, 0.8);
        pdf.text(PdfWriter.PAGE_WIDTH - M - 215, 98, 10, false, "Authorised signatory: ______________");
        return pdf.bytes();
    }

    // ---------------- layout helpers ----------------

    private static double row(PdfWriter pdf, double y, String label, String value) {
        pdf.text(M, y, 10, true, label + ":");
        pdf.text(M + 130, y, 10, false, value);
        return y - 16;
    }

    private static double para(PdfWriter pdf, double y, String heading, String text) {
        pdf.text(M, y, 11, true, heading);
        y -= 15;
        for (String line : wrap(text)) {
            if (y < 120) { pdf.newPage(); y = PdfWriter.PAGE_HEIGHT - 56; }
            pdf.text(M, y, 10, false, line);
            y -= 13;
        }
        return y;
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) { out.add("-"); return out; }
        for (String raw : text.replace("\r\n", "\n").split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : raw.split(" ")) {
                if (line.length() == 0) line.append(word);
                else if (line.length() + 1 + word.length() <= CHARS_PER_LINE) line.append(' ').append(word);
                else { out.add(line.toString()); line = new StringBuilder(word); }
            }
            out.add(line.length() == 0 ? " " : line.toString());
        }
        return out;
    }

    private static String dt(long millis) {
        return new SimpleDateFormat("dd MMM yyyy, HH:mm").format(new Date(millis));
    }

    private static String amount(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private Appointment appointment(String id) {
        Appointment a = id == null ? null : db.appointments.get(id.trim());
        if (a == null) throw new IllegalArgumentException("Consultation not found: " + id);
        return a;
    }

    private void requireAccess(Session s, Appointment a) {
        boolean ok = ("PATIENT".equals(s.role()) && a.getPatientId().equals(s.accountId()))
                  || ("DOCTOR".equals(s.role()) && a.getDoctorId().equals(s.accountId()))
                  || "ADMIN".equals(s.role());
        if (!ok) throw new IllegalArgumentException("You do not have access to this prescription.");
    }
}
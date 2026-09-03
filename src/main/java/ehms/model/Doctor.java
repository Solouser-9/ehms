package ehms.model;

import ehms.security.PasswordHasher;

import java.util.LinkedHashMap;
import java.util.Map;

public class Doctor implements Account {

    private static final long serialVersionUID = 1L;

    private final String id;
    private String name;
    private String email;
    private String password;              // PBKDF2 hash (field name kept for old ehms.dat files)
    private String phone;
    private String specialization;
    private String licenseNo;
    private double fee;
    private boolean available = true;
    private Boolean verified = false;     // null (legacy data) counts as verified
    private Boolean blocked = null;

    public Doctor(String id, String name, String email, String password, String phone,
                  String specialization, String licenseNo, double fee) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = PasswordHasher.isHashed(password) ? password : PasswordHasher.hash(password);
        this.phone = phone;
        this.specialization = specialization;
        this.licenseNo = licenseNo;
        this.fee = fee;
    }

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public String getEmail() { return email; }
    @Override public String getPasswordHash() { return password; }
    @Override public void setPasswordHash(String h) { this.password = h; }

    @Override public boolean isBlocked() { return Boolean.TRUE.equals(blocked); }
    @Override public void setBlocked(boolean b) { this.blocked = b; }
    @Override public Boolean getBlockedFlag() { return blocked; }
    @Override public void setBlockedFlag(Boolean b) { this.blocked = b; }

    public String getPhone() { return phone; }
    public String getSpecialization() { return specialization; }
    public String getLicenseNo() { return licenseNo; }
    public double getFee() { return fee; }
    public void setFee(double fee) { this.fee = fee; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    /** New doctors start unverified; doctors from legacy data (null) are grandfathered as verified. */
    public boolean isVerified() { return verified == null || verified; }
    public Boolean getVerifiedFlag() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public void setVerifiedFlag(Boolean verified) { this.verified = verified; }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("email", email);
        m.put("phone", phone);
        m.put("specialization", specialization);
        m.put("licenseNo", licenseNo);
        m.put("fee", fee);
        m.put("available", available);
        m.put("verified", isVerified());
        m.put("blocked", isBlocked());
        return m;
    }
}
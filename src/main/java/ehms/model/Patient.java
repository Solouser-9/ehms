package ehms.model;

import ehms.security.PasswordHasher;

import java.util.LinkedHashMap;
import java.util.Map;

public class Patient implements Account {

    private static final long serialVersionUID = 1L;

    private final String id;
    private String name;
    private String email;
    private String password;              // PBKDF2 hash
    private String phone;
    private int age;
    private String gender;
    private String bloodGroup;
    private String address;
    private Boolean blocked = null;

    public Patient(String id, String name, String email, String password, String phone,
                   int age, String gender, String bloodGroup, String address) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = PasswordHasher.isHashed(password) ? password : PasswordHasher.hash(password);
        this.phone = phone;
        this.age = age;
        this.gender = gender;
        this.bloodGroup = bloodGroup;
        this.address = address;
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
    public int getAge() { return age; }
    public String getGender() { return gender; }
    public String getBloodGroup() { return bloodGroup; }
    public String getAddress() { return address; }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("email", email);
        m.put("phone", phone);
        m.put("age", age);
        m.put("gender", gender);
        m.put("bloodGroup", bloodGroup);
        m.put("address", address);
        return m;
    }
}
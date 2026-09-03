package ehms.model;

import ehms.security.PasswordHasher;

import java.util.LinkedHashMap;
import java.util.Map;

/** System administrator: verifies doctor licences, blocks abusive accounts, sees global reports. */
public class Admin implements Account {

    private static final long serialVersionUID = 1L;

    private final String id;              // AD001
    private String name;
    private String email;
    private String password;              // PBKDF2 hash
    private Boolean blocked = null;

    public Admin(String id, String name, String email, String password) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = PasswordHasher.isHashed(password) ? password : PasswordHasher.hash(password);
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

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("email", email);
        return m;
    }
}
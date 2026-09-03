package ehms.model;

import java.io.Serializable;
import java.util.Map;

/** Common contract for any login-capable entity (doctor, patient, hospital, admin). */
public interface Account extends Serializable {
    String getId();
    String getName();
    String getEmail();

    String getPasswordHash();
    void setPasswordHash(String passwordHash);

    boolean isBlocked();
    void setBlocked(boolean blocked);

    /** Tri-state flag so legacy data (null = never blocked) round-trips through SQL stores. */
    Boolean getBlockedFlag();
    void setBlockedFlag(Boolean blocked);

    Map<String, Object> toMap();
}
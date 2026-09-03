package ehms.service;

public final class Validation {

    private Validation() {}

    public static String require(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " is required.");
        return value.trim();
    }

    public static String requireEmail(String value) {
        String email = require(value, "Email").toLowerCase();
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1)
            throw new IllegalArgumentException("Please enter a valid email address.");
        return email;
    }

    public static void requirePassword(String password) {
        require(password, "Password");
        if (password.length() < 4)
            throw new IllegalArgumentException("Password must be at least 4 characters long.");
    }

    public static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
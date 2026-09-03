package ehms.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every flag from the classic Main, as application.yml / env properties. */
@ConfigurationProperties("ehms")
public record EhmsProperties(
        String dbUrl, Integer backups, Long backupIntervalSeconds, Integer auditCap,
        String adminKey, Boolean strictVerification, Boolean prorate, Boolean trustProxy,
        Integer captchaDifficulty, String stripeKey, String paystackKey,
        String stripeCurrency, String publicUrl) {

    public EhmsProperties {
        if (dbUrl == null || dbUrl.isBlank()) dbUrl = "";
        if (backups == null) backups = 10;
        if (backupIntervalSeconds == null) backupIntervalSeconds = 60L;
        if (auditCap == null) auditCap = 2000;
        if (adminKey == null || adminKey.isBlank()) adminKey = "ehms-admin-key";
        if (strictVerification == null) strictVerification = false;
        if (prorate == null) prorate = false;
        if (trustProxy == null) trustProxy = false;
        if (captchaDifficulty == null) captchaDifficulty = 3;
        if (stripeKey == null || stripeKey.isBlank()) stripeKey = null;
        if (paystackKey == null || paystackKey.isBlank()) paystackKey = null;
        if (stripeCurrency == null || stripeCurrency.isBlank()) stripeCurrency = "ngn";
        if (publicUrl != null && publicUrl.isBlank()) publicUrl = null;
    }
}
package ehms.service;

/** Pluggable online-payment back-end. Mock = instant success (the historic behaviour). */
public interface PaymentGateway {

    String name();

    /** Legacy bridge: checkout without an email (mock ignores it; Paystack will reject it). */
    default String checkout(String kind, String id, double amount, String currency,
                            String description, String baseUrl) throws Exception {
        return checkout(kind, id, amount, currency, description, baseUrl, null);
    }

    /**
     * Starts an online payment with the payer's email (Paystack requires it).
     * Returns null when the payment settles instantly (mock); otherwise a redirect URL.
     */
    String checkout(String kind, String id, double amount, String currency,
                    String description, String baseUrl, String email) throws Exception;

    /** Server-side verification that the external reference is actually settled. */
    boolean verify(String kind, String id, String externalRef) throws Exception;
}
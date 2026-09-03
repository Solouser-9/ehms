package ehms.service;

/** The built-in gateway: payments settle immediately (demo/teaching mode). */
public final class MockGateway implements PaymentGateway {
    @Override public String name() { return "mock"; }
    @Override public String checkout(String kind, String id, double amount, String currency,
                                     String description, String baseUrl, String email) { return null; }
    @Override public boolean verify(String kind, String id, String externalRef) { return true; }
}
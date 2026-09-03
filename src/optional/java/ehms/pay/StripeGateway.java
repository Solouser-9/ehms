package ehms.pay;

import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import ehms.service.PaymentGateway;

/** Stripe Checkout (redirect flow). Works end-to-end with TEST-mode keys; live keys just work. */
public final class StripeGateway implements PaymentGateway {

    private final String apiKey;
    private final String currency;

    public StripeGateway(String apiKey, String currency) {
        this.apiKey = apiKey;
        this.currency = currency == null || currency.isBlank() ? "ngn" : currency.toLowerCase();
    }

    @Override public String name() { return "stripe"; }

    @Override
    public String checkout(String kind, String id, double amount, String currency,
                           String description, String baseUrl, String email) throws Exception {
        Stripe.apiKey = apiKey;
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(baseUrl + "/api/payment/stripe/return?type=" + kind
                        + "&id=" + id + "&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(baseUrl + "/")
                .putMetadata("kind", kind)
                .putMetadata("id", id)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(this.currency)
                                .setUnitAmount(Math.round(amount * 100))   // paise / kobo / cents
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(description).build())
                                .build())
                        .build());
        if (email != null && !email.isBlank()) params.setCustomerEmail(email);
        return Session.create(params.build()).getUrl();
    }

    @Override
    public boolean verify(String kind, String id, String externalRef) throws Exception {
        Stripe.apiKey = apiKey;
        Session s = Session.retrieve(externalRef);
        return "paid".equals(s.getPaymentStatus())
                && kind.equals(s.getMetadata().get("kind"))
                && id.equals(s.getMetadata().get("id"));
    }
}
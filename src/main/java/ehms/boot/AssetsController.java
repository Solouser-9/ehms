package ehms.boot;

import ehms.security.SessionManager.Session;
import ehms.service.AuditService;
import ehms.service.BillingService;
import ehms.service.PaymentGateway;
import ehms.service.PaymentService;
import ehms.service.PdfService;
import ehms.service.ReportService;
import ehms.util.Log;
import ehms.web.PwaAssets;
import ehms.web.WebUi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class AssetsController {

    private final ReportService reports;
    private final PdfService pdfs;
    private final PaymentGateway gateway;
    private final AuditService audit;
    private final PaymentService payments;
    private final BillingService billing;

    public AssetsController(ReportService reports, PdfService pdfs, PaymentGateway gateway,
                            AuditService audit, PaymentService payments, BillingService billing) {
        this.reports = reports; this.pdfs = pdfs; this.gateway = gateway;
        this.audit = audit; this.payments = payments; this.billing = billing;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() { return WebUi.index(); }

    @GetMapping(value = "/manifest.webmanifest", produces = "application/manifest+json; charset=utf-8")
    public String manifest() { return PwaAssets.manifest(); }

    @GetMapping(value = "/sw.js", produces = "application/javascript; charset=utf-8")
    public String serviceWorker() { return PwaAssets.serviceWorker(); }

    @GetMapping("/icon-{size}.png")
    public ResponseEntity<byte[]> icon(@PathVariable int size) {
        byte[] png = PwaAssets.icon(size == 192 ? 192 : 512);
        return png == null ? ResponseEntity.internalServerError().body("Icon unavailable.".getBytes())
                : ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400").body(png);
    }

    @GetMapping("/api/file")
    public ResponseEntity<byte[]> file(@RequestParam String id, HttpServletRequest req) {
        Session s = Params.session(req);
        if (s == null) throw new AuthException("Please sign in to view reports.");
        ReportService.Loaded l = reports.load(s, id);
        String ctype = l.attachment().getContentType() == null
                ? "application/octet-stream" : l.attachment().getContentType();
        return inline(ctype, l.data(), l.attachment().getFileName());
    }

    @GetMapping("/api/prescription/pdf")
    public ResponseEntity<byte[]> prescriptionPdf(@RequestParam String appointmentId, HttpServletRequest req) {
        Session s = Params.session(req);
        if (s == null) throw new AuthException("Please sign in.");
        return inline("application/pdf", pdfs.prescription(s, appointmentId),
                "prescription-" + appointmentId + ".pdf");
    }

    @GetMapping("/api/history/pdf")
    public ResponseEntity<byte[]> historyPdf(HttpServletRequest req) {
        Session s = Params.session(req);
        if (s == null) throw new AuthException("Please sign in.");
        return inline("application/pdf", pdfs.history(s), "my-prescriptions.pdf");
    }

    @GetMapping("/api/bill/pdf")
    public ResponseEntity<byte[]> billPdf(@RequestParam String billId,
                                          @RequestParam(name = "cur", defaultValue = "\u20A6") String cur,
                                          HttpServletRequest req) {
        Session s = Params.session(req);
        if (s == null) throw new AuthException("Please sign in.");
        return inline("application/pdf", pdfs.bill(s, billId, cur), "bill-" + billId + ".pdf");
    }

    @GetMapping("/api/payment/stripe/return")
    public ResponseEntity<Void> stripeReturn(@RequestParam String type, @RequestParam String id,
                                             @RequestParam("session_id") String ref,
                                             HttpServletRequest req) {
        boolean paid = false;
        try { paid = gateway.verify(type, id, ref); }
        catch (Exception e) { Log.warn("Stripe verification failed: " + e); }
        if (paid) {
            try {
                if ("payment".equals(type)) payments.gatewayPaid(id, "STRIPE");
                else if ("bill".equals(type)) billing.gatewayPaid(id, "STRIPE");
                Session s = Params.session(req);
                if (s != null) audit.record(s, AuditService.PAYMENT_MADE,
                        "Paid " + type + " " + id + " online via Stripe");
            } catch (IllegalArgumentException notFound) {
                Log.warn("Stripe callback for unknown " + type + ": " + id);
            }
            return ResponseEntity.status(302).location(URI.create("/?paid=1")).build();
        }
        return ResponseEntity.status(302).location(URI.create("/?paid=0")).build();
    }

    @GetMapping("/api/payment/paystack/return")
    public ResponseEntity<Void> paystackReturn(@RequestParam String type, @RequestParam String id,
                                               @RequestParam(value = "reference", required = false) String reference,
                                               @RequestParam(value = "trxref", required = false) String trxref,
                                               HttpServletRequest req) {
        String ref = reference != null ? reference : trxref;
        boolean paid = false;
        try { paid = gateway.verify(type, id, ref); }
        catch (Exception e) { Log.warn("Paystack verification failed: " + e); }
        if (paid) {
            try {
                if ("payment".equals(type)) payments.gatewayPaid(id, "PAYSTACK");
                else if ("bill".equals(type)) billing.gatewayPaid(id, "PAYSTACK");
                Session s = Params.session(req);
                if (s != null) audit.record(s, AuditService.PAYMENT_MADE,
                        "Paid " + type + " " + id + " online via Paystack");
            } catch (IllegalArgumentException notFound) {
                Log.warn("Paystack callback for unknown " + type + ": " + id);
            }
            return ResponseEntity.status(302).location(URI.create("/?paid=1")).build();
        }
        return ResponseEntity.status(302).location(URI.create("/?paid=0")).build();
    }

    private static ResponseEntity<byte[]> inline(String contentType, byte[] body, String fileName) {
        String safe = fileName == null || fileName.isBlank()
                ? "download.bin" : fileName.replaceAll("[^A-Za-z0-9._ ()-]", "_");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safe + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
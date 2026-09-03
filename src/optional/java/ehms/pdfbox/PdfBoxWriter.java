package ehms.pdfbox;

import ehms.util.Log;
import ehms.util.PdfWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unicode PDF rendering (full Noto Sans coverage: diacritics, tone marks,
 * hooked letters, currency symbols) backed by PDFBox. The Noto Sans TTFs are
 * loaded from fonts/ on first use and downloaded once from the Noto project
 * when absent (disable with -Dehms.font.download=false). If the fonts cannot
 * be obtained, the constructor throws and PdfFactory falls back to the
 * pure-Java writer.
 */
public final class PdfBoxWriter implements PdfWriter {

    private static final Path FONT_DIR = Path.of("fonts");
    private static final String REG = "NotoSans-Regular.ttf";
    private static final String BOLD = "NotoSans-Bold.ttf";
    private static final String BASE = "https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/NotoSans/hinted/ttf/";

    private static volatile byte[] regBytes, boldBytes;

    private final PDDocument doc = new PDDocument();
    private PDPageContentStream cs;
    private final PDType0Font fReg, fBold;

    public PdfBoxWriter() {
        try {
            fReg = PDType0Font.load(doc, new ByteArrayInputStream(fontBytes(false)));
            fBold = PDType0Font.load(doc, new ByteArrayInputStream(fontBytes(true)));
            newPage();
        } catch (Exception e) {
            try { doc.close(); } catch (Exception ignored) { }
            throw new IllegalStateException("Unicode fonts unavailable: " + e, e);
        }
    }

    @Override public PdfWriter text(double x, double y, double size, boolean bold, String text) {
        try {
            cs.beginText();
            cs.setFont(bold ? fBold : fReg, (float) size);
            cs.newLineAtOffset((float) x, (float) y);
            cs.showText(text == null ? "" : text);
            cs.endText();
        } catch (Exception e) { throw new IllegalStateException(e); }
        return this;
    }

    @Override public PdfWriter line(double x1, double y1, double x2, double y2, double width) {
        try {
            cs.setLineWidth((float) width);
            cs.moveTo((float) x1, (float) y1);
            cs.lineTo((float) x2, (float) y2);
            cs.stroke();
        } catch (Exception e) { throw new IllegalStateException(e); }
        return this;
    }

    @Override public PdfWriter greyBar(double x, double y, double w, double h, double grey) {
        try {
            float g = (float) grey;
            cs.setNonStrokingColor(g, g, g);
            cs.addRect((float) x, (float) y, (float) w, (float) h);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f);
        } catch (Exception e) { throw new IllegalStateException(e); }
        return this;
    }

    @Override public PdfWriter newPage() {
        try {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
        } catch (Exception e) { throw new IllegalStateException(e); }
        return this;
    }

    @Override public byte[] bytes() {
        try {
            cs.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] fontBytes(boolean bold) throws Exception {
        byte[] cached = bold ? boldBytes : regBytes;
        if (cached != null) return cached;
        String name = bold ? BOLD : REG;
        Path file = FONT_DIR.resolve(name);
        byte[] data;
        if (Files.exists(file)) {
            data = Files.readAllBytes(file);
        } else if (Boolean.parseBoolean(System.getProperty("ehms.font.download", "true"))) {
            Log.info("Downloading Unicode font " + name + " (one-time) ...");
            data = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(BASE + name)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            try {
                Files.createDirectories(FONT_DIR);
                Files.write(file, data);
            } catch (Exception saveFailed) { /* usable from memory even if not cached */ }
        } else {
            throw new IllegalStateException("font " + name + " missing and download disabled");
        }
        if (bold) boldBytes = data; else regBytes = data;
        return data;
    }
}
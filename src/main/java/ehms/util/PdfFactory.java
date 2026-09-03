package ehms.util;

/** Chooses the Unicode (PDFBox) writer when available, else the pure-Java ASCII writer. */
public final class PdfFactory {

    public static PdfWriter create() {
        try {
            return (PdfWriter) Class.forName("ehms.pdfbox.PdfBoxWriter")
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable unavailable) {
            return new Pdf();   // zero-dependency fallback: ASCII transliteration
        }
    }

    private PdfFactory() {}
}
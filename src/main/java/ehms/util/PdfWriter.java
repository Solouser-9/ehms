package ehms.util;

/** Drawing API shared by the pure-Java writer and the PDFBox-backed Unicode writer. */
public interface PdfWriter {
    double PAGE_WIDTH = 595;
    double PAGE_HEIGHT = 842;

    PdfWriter text(double x, double y, double size, boolean bold, String text);
    PdfWriter line(double x1, double y1, double x2, double y2, double width);
    PdfWriter greyBar(double x, double y, double w, double h, double grey);
    PdfWriter newPage();
    byte[] bytes();
}
package ehms.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A minimal PDF 1.4 writer with no external libraries. Supports text in the
 * built-in Helvetica fonts, stroked lines and grey rectangles on A4 pages.
 * Coordinates have their origin at the bottom-left corner of the page.
 */
public final class Pdf implements PdfWriter {

    private final List<StringBuilder> streams = new ArrayList<>();

    public Pdf() { streams.add(new StringBuilder()); }

    @Override
    public PdfWriter newPage() { streams.add(new StringBuilder()); return this; }

    @Override
    public PdfWriter text(double x, double y, double size, boolean bold, String text) {
        cur().append("BT /F").append(bold ? '2' : '1').append(' ').append(num(size))
             .append(" Tf 1 0 0 1 ").append(num(x)).append(' ').append(num(y))
             .append(" Tm (").append(escape(text)).append(") Tj ET\n");
        return this;
    }

    @Override
    public PdfWriter line(double x1, double y1, double x2, double y2, double width) {
        cur().append(num(width)).append(" w ").append(num(x1)).append(' ').append(num(y1))
             .append(" m ").append(num(x2)).append(' ').append(num(y2)).append(" l S\n");
        return this;
    }

    @Override
    public PdfWriter greyBar(double x, double y, double w, double h, double grey) {
        cur().append(num(grey)).append(" g ").append(num(x)).append(' ').append(num(y)).append(' ')
             .append(num(w)).append(' ').append(num(h)).append(" re f 0 g\n");
        return this;
    }

    @Override
    public byte[] bytes() {
        int pages = streams.size();
        List<byte[]> objects = new ArrayList<>();                     // object (i+1) at index i

        objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            if (i > 0) kids.append(' ');
            kids.append(5 + 2 * i).append(" 0 R");
        }
        objects.add(ascii("<< /Type /Pages /Kids [ " + kids + " ] /Count " + pages + " >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));

        for (int i = 0; i < pages; i++) {
            int pageObj = 5 + 2 * i, contentObj = 6 + 2 * i;
            objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                    + (int) PAGE_WIDTH + " " + (int) PAGE_HEIGHT
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + contentObj + " 0 R >>"));
            byte[] content = streams.get(i).toString().getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(ascii("<< /Length " + content.length + " >>\nstream\n"));
            o.writeBytes(content);
            o.writeBytes(ascii("\nendstream"));
            objects.add(o.toByteArray());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("%PDF-1.4\n"));
        long[] offsets = new long[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            out.writeBytes(ascii((i + 1) + " 0 obj\n"));
            out.writeBytes(objects.get(i));
            out.writeBytes(ascii("\nendobj\n"));
        }
        long xref = out.size();
        out.writeBytes(ascii("xref\n0 " + (objects.size() + 1) + "\n"));
        out.writeBytes(ascii("0000000000 65535 f \n"));
        for (int i = 1; i <= objects.size(); i++) {
            out.writeBytes(ascii(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i])));
        }
        out.writeBytes(ascii("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF"));
        return out.toByteArray();
    }

    private StringBuilder cur() { return streams.get(streams.size() - 1); }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == ')' || c == '\\') sb.append('\\').append(c);
            else if (c >= 32 && c < 127) sb.append(c);
            else if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else sb.append('?');
        }
        return sb.toString();
    }

    private static String num(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
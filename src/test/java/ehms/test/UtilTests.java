package ehms.test;

import ehms.security.PasswordHasher;
import ehms.service.Validation;
import ehms.util.Json;
import ehms.util.Multipart;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class UtilTests extends EhmsTest {

    @TestFactory
    Stream<DynamicTest> all() { return junitTests(); }

    @Override protected void define() {

        test("PasswordHasher: correct password verifies", () -> {
            String hash = PasswordHasher.hash("secret123");
            assertTrue(PasswordHasher.verify("secret123", hash));
        });
        test("PasswordHasher: wrong password fails", () ->
            assertFalse(PasswordHasher.verify("secret456", PasswordHasher.hash("secret123"))));
        test("PasswordHasher: unique salt per hash", () ->
            assertFalse(PasswordHasher.hash("same").equals(PasswordHasher.hash("same"))));
        test("PasswordHasher: isHashed", () -> {
            assertTrue(PasswordHasher.isHashed(PasswordHasher.hash("x")));
            assertFalse(PasswordHasher.isHashed("plaintext"));
        });

        test("Validation: require rejects blank", () ->
            assertThrowsWithMessage(IllegalArgumentException.class, "required",
                () -> Validation.require("  ", "Name")));
        test("Validation: email normalised and validated", () -> {
            assertEquals("a@b.com", Validation.requireEmail("  A@B.COM "));
            assertThrows(IllegalArgumentException.class, () -> Validation.requireEmail("not-an-email"));
        });
        test("Validation: short password rejected", () ->
            assertThrows(IllegalArgumentException.class, () -> Validation.requirePassword("abc")));

        test("Json: write/parse round trip", () -> {
            Map<String, Object> m = Json.obj("name", "A & B <c>", "count", 5,
                    "ok", true, "list", List.of(1L, 2L));
            Object back = Json.parse(Json.write(m));
            assertTrue(back instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) back;
            assertEquals("A & B <c>", map.get("name"));
            assertEquals(5L, map.get("count"));
            assertEquals(Boolean.TRUE, map.get("ok"));
            assertEquals(2, ((List<?>) map.get("list")).size());
        });
        test("Json: malformed input throws", () ->
            assertThrows(IllegalArgumentException.class, () -> Json.parse("{oops")));

        test("Multipart: fields, filename, content type and bytes", () -> {
            String body = "--XyZ\r\n"
                    + "Content-Disposition: form-data; name=\"title\"\r\n\r\nMy report\r\n"
                    + "--XyZ\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"r.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\nAB\r\n"
                    + "--XyZ--";
            Map<String, Object> parts = Multipart.parse("multipart/form-data; boundary=XyZ",
                    body.getBytes(StandardCharsets.ISO_8859_1));
            assertEquals("My report", parts.get("title"));
            assertEquals("r.png", parts.get(Multipart.FILENAME));
            assertEquals("image/png", parts.get(Multipart.CONTENT_TYPE));
            assertEquals(2, ((byte[]) parts.get(Multipart.BYTES)).length);
        });
        test("Multipart: missing boundary throws", () ->
            assertThrows(IllegalArgumentException.class,
                () -> Multipart.parse("multipart/form-data", new byte[0])));
    }
}
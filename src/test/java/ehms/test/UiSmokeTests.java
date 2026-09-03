package ehms.test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import ehms.db.Database;
import ehms.util.Json;
import ehms.web.WebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Browser UI smoke tests (Playwright + Chromium). OPT-IN because they download
 * a ~150 MB browser on first use:
 *
 *   mvn test-compile exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
 *
 *   mvn test -Dtest=UiSmokeTests -Dsurefire.excludedGroups=
 */
@Tag("ui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiSmokeTests {

    private HttpServer server;
    private String base;
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void startServerAndBrowser() throws Exception {
        Database db = Database.createDetached();
        server = WebServer.start(db, new ehms.web.WebServerConfig(0, false, "ui-admin-key",
                false, false, 0, null, null, "inr", null));
        base = "http://localhost:" + server.getAddress().getPort();

        // Seed one patient straight through the API (registration needs no session).
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/register/patient"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Json.obj(
                        "name", "UI Tester", "email", "ui@test.io", "password", "pass1234",
                        "phone", "9810000000", "age", 30, "gender", "Male",
                        "bloodGroup", "B+", "address", "Test City"))))
                .build();
        HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopEverything() {
        browser.close();
        playwright.close();
        server.stop(0);
    }

    @Test
    void homePageLoadsStatsAndPwaAssets() {
        Page page = browser.newPage();
        page.navigate(base + "/");
        assertTrue(page.title().contains("E-HealthCare"));
        page.waitForSelector("#stats .stat");
        assertTrue(page.locator("#stats .stat").count() >= 4);
        assertEquals(1, page.locator("link[rel=manifest]").count());
        page.close();
    }

    @Test
    void darkModeTogglesAndSurvivesReload() {
        Page page = browser.newPage();
        page.navigate(base + "/");
        String before = page.locator("html").getAttribute("data-theme");
        page.locator("#theme-toggle").click();
        String after = page.locator("html").getAttribute("data-theme");
        assertNotEquals(before, after);
        page.reload();
        assertEquals(after, page.locator("html").getAttribute("data-theme"));
        page.close();
    }

    @Test
    void languageSwitchRendersHindiNav() {
        Page page = browser.newPage();
        page.navigate(base + "/");
        page.locator("#lang").selectOption("hi");
        assertEquals("\u0918\u0930", page.locator("nav button").first().innerText());   // "घर" = Home
        page.close();
    }

    @Test
    void patientCanSignInAndSeeDashboard() {
        Page page = browser.newPage();
        page.navigate(base + "/");
        page.locator("[data-go=login]").click();
        page.locator("#l-role").selectOption("PATIENT");
        page.locator("#l-email").fill("ui@test.io");
        page.locator("#l-pass").fill("pass1234");
        page.locator("#l-go").click();
        page.waitForSelector("[data-go=patient]");
        assertTrue(page.locator("[data-go=patient]").innerText().contains("My Health"));
        page.close();
    }

    @Test
    void homePagePassesAxeAccessibilityAudit() {
        Page page = browser.newPage();
        page.navigate(base + "/");
        page.waitForSelector("#stats .stat");
        try {
            page.addScriptTag(new Page.AddScriptTagOptions()
                    .setUrl("https://cdn.jsdelivr.net/npm/axe-core@4.9.1/axe.min.js"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> violations = (List<Map<String, Object>>) page.evaluate(
                "async () => (await axe.run(document)).violations.map(v => ({id: v.id, impact: v.impact, nodes: v.nodes.length}))");
            assertTrue(violations.isEmpty(), "axe violations on the home page: " + violations);
        } catch (RuntimeException offline) {          // CDN unreachable - skip rather than fail
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "axe-core CDN unavailable - audit skipped");
        }
        page.close();
    }
}
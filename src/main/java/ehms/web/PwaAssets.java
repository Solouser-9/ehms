package ehms.web;

import ehms.util.Log;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * PWA assets generated at runtime with the JDK only: app icons, the web app
 * manifest and the service worker (caches the app shell so the UI opens offline).
 */
public final class PwaAssets {

    private PwaAssets() {}

    private static volatile byte[] icon192;
    private static volatile byte[] icon512;

    /** PNG bytes for the requested icon size (192 or 512), or null if drawing failed. */
    public static byte[] icon(int size) {
        if (size == 192 && icon192 != null) return icon192;
        if (size == 512 && icon512 != null) return icon512;
        try {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, new Color(14, 165, 233), size, size, new Color(7, 89, 133)));
                float r = size * 0.22f;
                g.fill(new RoundRectangle2D.Float(0, 0, size, size, r, r));
                g.setColor(Color.WHITE);
                int c = size / 2;
                int arm = Math.round(size * 0.30f);
                int thick = Math.round(size * 0.17f);
                g.fillRect(c - thick / 2, c - arm, thick, 2 * arm);
                g.fillRect(c - arm, c - thick / 2, 2 * arm, thick);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            byte[] png = out.toByteArray();
            if (size == 192) icon192 = png;
            else if (size == 512) icon512 = png;
            return png;
        } catch (Exception e) {
            Log.warn("Could not generate " + size + "px icon: " + e);
            return null;
        }
    }

    public static String manifest() {
        return """
{
  "name": "E-HealthCare Management System",
  "short_name": "E-HealthCare",
  "description": "Virtual doctor consultations, prescriptions, hospital bed tracking, pharmacy and payments.",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "orientation": "portrait-primary",
  "background_color": "#f1f5f9",
  "theme_color": "#0284c7",
  "icons": [
    { "src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
    { "src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" },
    { "src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
  ]
}
""";
    }

    public static String serviceWorker() {
        return """
// E-HealthCare service worker: caches the app shell so the UI opens offline.
// API calls are NEVER cached - medical data must always be live.
const CACHE = 'ehms-shell-v1';
const SHELL = ['/', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/')) return;
  if (e.request.mode === 'navigate') {
    e.respondWith(fetch(e.request).catch(() => caches.match('/')));
    return;
  }
  e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request)));
});
""";
    }
}
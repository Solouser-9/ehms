package ehms.boot;

import ehms.db.Database;
import ehms.model.Appointment;
import ehms.security.SessionManager;
import ehms.service.ChatService;
import ehms.util.Json;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat/stats push on the SAME port via Spring WebSocket (Tomcat). Same rooms
 * ("stats" + consultation ids), same cookie authentication, same message shapes.
 */
@Component
public class EhmsWebSocketHandler extends TextWebSocketHandler {

    private final Database db;
    private final SessionManager sessions;
    private final ChatService chat;
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public EhmsWebSocketHandler(Database db, SessionManager sessions, ChatService chat) {
        this.db = db; this.sessions = sessions; this.chat = chat;
    }

    public void push(String room, String json) {
        Set<WebSocketSession> set = rooms.get(room);
        if (set == null) return;
        for (WebSocketSession s : set.toArray(new WebSocketSession[0])) {
            try { synchronized (s) { s.sendMessage(new TextMessage(json)); } }
            catch (Exception broken) { set.remove(s); }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sess) throws Exception {
        String token = null;
        String cookie = sess.getHandshakeHeaders().getFirst(HttpHeaders.COOKIE);
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && SessionManager.COOKIE_NAME.equals(kv[0])) token = kv[1];
            }
        }
        SessionManager.Session user = token == null ? null : sessions.get(token);
        if (user == null) sess.close(new CloseStatus(4001, "unauthenticated"));
        else sess.getAttributes().put("ehms.user", user);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession sess, TextMessage message) throws Exception {
        SessionManager.Session user = (SessionManager.Session) sess.getAttributes().get("ehms.user");
        if (user == null) return;
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(message.getPayload());
            String action = String.valueOf(m.getOrDefault("action", ""));
            String room = m.get("room") == null ? null : String.valueOf(m.get("room"));
            switch (action) {
                case "sub" -> {
                    if (allowed(user, room)) {
                        sess.getAttributes().compute("ehms.rooms",
                                (k, v) -> { Set<String> set = v == null
                                        ? ConcurrentHashMap.newKeySet() : (Set<String>) v; set.add(room); return set; });
                        rooms.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(sess);
                    }
                }
                case "unsub" -> leave(sess, room);
                case "send" -> {
                    String text = String.valueOf(m.getOrDefault("text", "")).trim();
                    if (room != null && !text.isEmpty() && allowed(user, room)) {
                        Map<String, Object> msg = chat.send(user, room, text);
                        push(room, Json.write(Json.obj("type", "msg", "message", msg)));
                    }
                }
                default -> { }
            }
        } catch (IllegalArgumentException rejected) {
            try { synchronized (sess) { sess.sendMessage(new TextMessage(
                    Json.write(Json.obj("type", "error", "error", rejected.getMessage())))); } }
            catch (Exception ignored) { }
        } catch (Exception ignored) { }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sess, CloseStatus status) {
        Object roomsAttr = sess.getAttributes().get("ehms.rooms");
        if (roomsAttr instanceof Set<?> joined) {
            for (Object room : joined.toArray()) leave(sess, String.valueOf(room));
        }
    }

    private void leave(WebSocketSession sess, String room) {
        if (room == null) return;
        Set<WebSocketSession> set = rooms.get(room);
        if (set != null) {
            set.remove(sess);
            if (set.isEmpty()) rooms.remove(room);
        }
        Object roomsAttr = sess.getAttributes().get("ehms.rooms");
        if (roomsAttr instanceof Set<?> joined) joined.remove(room);
    }

    private boolean allowed(SessionManager.Session user, String room) {
        if (room == null) return false;
        if ("stats".equals(room)) return true;
        Appointment a = db.appointments.get(room);
        if (a == null) return false;
        return ("PATIENT".equals(user.role()) && a.getPatientId().equals(user.accountId()))
            || ("DOCTOR".equals(user.role()) && a.getDoctorId().equals(user.accountId()));
    }
}

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final EhmsWebSocketHandler handler;

    WebSocketConfig(EhmsWebSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins("*");
    }
}
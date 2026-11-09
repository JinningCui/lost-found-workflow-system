package com.lostfound.workflow;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    public record User(String id, String username, String role) {}
    private record Session(User user, Instant expiresAt) {}
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> passwords = Map.of(
            "admin1", "admin123", "admin2", "admin123", "staff", "staff123", "student", "student123");
    private final Map<String, User> users = Map.of(
            "admin1", new User("admin1", "admin1", "ADMIN"),
            "admin2", new User("admin2", "admin2", "ADMIN"),
            "staff", new User("staff", "staff", "STAFF"),
            "student", new User("student", "student", "STUDENT"));

    public Map<String, Object> login(String username, String password) {
        if (username == null || !passwords.getOrDefault(username, "").equals(password)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        User user = users.get(username);
        sessions.put(token, new Session(user, Instant.now().plusSeconds(8 * 3600)));
        return Map.of("token", token, "user", user);
    }

    public User current(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        Session session = sessions.get(authorization.substring(7));
        if (session == null || Instant.now().isAfter(session.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期");
        }
        return session.user();
    }

    public User require(HttpServletRequest request, String... roles) {
        User user = current(request);
        for (String role : roles) {
            if (role.equals(user.role())) return user;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权执行此操作");
    }
}

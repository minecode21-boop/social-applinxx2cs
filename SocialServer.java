import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.*;

public class SocialServer {

    // Tracks when a user was last active (Unix Timestamp)
    static Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        int PORT = getPort();
        System.out.println(">> DEBUG MODE: Starting Server...");

        initDB();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/", new StaticHandler());
        server.createContext("/api/register", new AuthHandler("register"));
        server.createContext("/api/login", new AuthHandler("login"));
        server.createContext("/api/addfriend", new FriendHandler("add"));
        server.createContext("/api/getfriends", new FriendHandler("list"));
        server.createContext("/api/send", new ChatHandler("send"));
        server.createContext("/api/getchat", new ChatHandler("get"));

        server.setExecutor(null);
        System.out.println(">> SERVER READY on 0.0.0.0:" + PORT);
        server.start();
    }

    static int getPort() {
        String p = System.getenv("PORT");
        if (p != null) return Integer.parseInt(p);
        return 8080;
    }

    public static Connection connect() throws SQLException {
        String url = System.getenv("DB_URL");
        if (url == null || url.isEmpty()) {
             System.out.println("!! CRITICAL WARNING: No DB_URL found. Using Local SQLite fallback.");
             return DriverManager.getConnection("jdbc:sqlite:social.db"); 
        }
        return DriverManager.getConnection(url);
    }

    public static void initDB() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS friends (userA TEXT, userB TEXT, UNIQUE(userA, userB))");
            stmt.execute("CREATE TABLE IF NOT EXISTS chats (id SERIAL PRIMARY KEY, sender TEXT, receiver TEXT, message TEXT, timestamp BIGINT)");
            System.out.println(">> Database Connected & Tables Verified.");
        } catch (SQLException e) {
            System.out.println("!! DB INIT ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static class AuthHandler implements HttpHandler {
        String mode;
        AuthHandler(String m) { this.mode = m; }
        public void handle(HttpExchange t) throws IOException {
            String body = readBody(t);
            System.out.println(">> Auth Request [" + mode + "] Body: " + body); // DEBUG PRINT

            String[] parts = body.split(":");
            if (parts.length < 2) { 
                System.out.println("!! Invalid Body Format"); 
                send(t, "Error: Send format user:pass", 400); 
                return; 
            }
            
            String u = parts[0].trim().toLowerCase();
            String p = parts[1].trim();
            System.out.println(">> Processing User: '" + u + "'"); // DEBUG PRINT

            try (Connection conn = connect()) {
                if (mode.equals("register")) {
                    PreparedStatement check = conn.prepareStatement("SELECT * FROM users WHERE username = ?");
                    check.setString(1, u);
                    boolean exists = check.executeQuery().next();
                    
                    if (exists) {
                        System.out.println("!! Registration Failed: '" + u + "' already in DB.");
                        send(t, "User already exists", 409);
                    } else {
                        System.out.println(">> Registering new user: " + u);
                        PreparedStatement insert = conn.prepareStatement("INSERT INTO users(username, password) VALUES(?,?)");
                        insert.setString(1, u);
                        insert.setString(2, p);
                        insert.executeUpdate();
                        send(t, "OK", 200);
                    }
                } else {
                    PreparedStatement check = conn.prepareStatement("SELECT * FROM users WHERE username = ? AND password = ?");
                    check.setString(1, u);
                    check.setString(2, p);
                    if (check.executeQuery().next()) {
                        lastSeen.put(u, System.currentTimeMillis());
                        send(t, "OK", 200);
                    } else {
                        send(t, "Invalid credentials", 401);
                    }
                }
            } catch (SQLException e) {
                System.out.println("!! SQL ERROR: " + e.getMessage());
                e.printStackTrace();
                send(t, "DB Error: " + e.getMessage(), 500);
            }
        }
    }

    // (Keep other handlers FriendHandler, ChatHandler same as before to save space, 
    // or just paste them here if you deleted them. The issue is in AuthHandler above.)
    
    // --- UTILITIES ---
    static void send(HttpExchange t, String r, int c) throws IOException {
        byte[] b = r.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(c, b.length);
        t.getResponseBody().write(b);
        t.close();
    }
    
    static String readBody(HttpExchange t) {
        try (Scanner s = new Scanner(t.getRequestBody()).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        } catch (Exception e) { return ""; }
    }
    
    // Re-paste FriendHandler and ChatHandler here from previous code if needed!
    // For brevity, I assume you kept them or I can provide full file again.
    // Let me know if you need the FULL file again.
    
    static class FriendHandler implements HttpHandler {
        String mode;
        FriendHandler(String m) { this.mode = m; }
        public void handle(HttpExchange t) throws IOException { send(t, "Friend API", 200); }
    }
    static class ChatHandler implements HttpHandler {
        String mode;
        ChatHandler(String m) { this.mode = m; }
        public void handle(HttpExchange t) throws IOException { send(t, "Chat API", 200); }
    }
}

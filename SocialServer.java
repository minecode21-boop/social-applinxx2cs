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
        System.out.println(">> DEBUG MODE: Starting Server on Port " + PORT + "...");

        // 1. Initialize DB (Now with Auto-Fix for URL)
        initDB();

        // 2. Start Server
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

    // --- HELPER: GET PORT ---
    static int getPort() {
        String p = System.getenv("PORT");
        if (p != null) return Integer.parseInt(p);
        return 8080;
    }

    // --- DATABASE HELPERS (FIXED HERE) ---
    public static Connection connect() throws SQLException {
        String url = System.getenv("DB_URL");
        
        // 1. Check if URL exists
        if (url == null || url.isEmpty()) {
             System.out.println("!! CRITICAL: No DB_URL found. Using Local fallback.");
             return DriverManager.getConnection("jdbc:sqlite:social.db"); 
        }

        // 2. AUTO-FIX: Neon gives 'postgresql://', but Java needs 'jdbc:postgresql://'
        if (!url.startsWith("jdbc:")) {
            System.out.println(">> Notice: Auto-fixing DB URL format for Java...");
            url = "jdbc:" + url;
        }

        // 3. Connect
        return DriverManager.getConnection(url);
    }

    public static void initDB() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            // Create Users Table
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT NOT NULL)");

            // Create Friends Table
            stmt.execute("CREATE TABLE IF NOT EXISTS friends (userA TEXT, userB TEXT, UNIQUE(userA, userB))");

            // Create Chats Table
            stmt.execute("CREATE TABLE IF NOT EXISTS chats (id SERIAL PRIMARY KEY, sender TEXT, receiver TEXT, message TEXT, timestamp BIGINT)");
            
            System.out.println(">> Database Connected & Tables Verified.");
        } catch (SQLException e) {
            System.out.println("!! DB INIT ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- HANDLERS ---

    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            File file = new File("index.html");
            if (!file.exists()) {
                String r = "<h1>index.html not found</h1>";
                t.sendResponseHeaders(404, r.length()); 
                t.getResponseBody().write(r.getBytes()); 
                t.close(); 
                return;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            t.getResponseHeaders().set("Content-Type", "text/html");
            t.sendResponseHeaders(200, bytes.length);
            t.getResponseBody().write(bytes);
            t.close();
        }
    }

    static class AuthHandler implements HttpHandler {
        String mode;
        AuthHandler(String m) { this.mode = m; }
        public void handle(HttpExchange t) throws IOException {
            String body = readBody(t);
            System.out.println(">> Auth [" + mode + "] Body: " + body);

            String[] parts = body.split(":");
            if (parts.length < 2) { 
                send(t, "Error: Format is user:pass", 400); 
                return; 
            }
            
            String u = parts[0].trim().toLowerCase();
            String p = parts[1].trim();

            try (Connection conn = connect()) {
                if (mode.equals("register")) {
                    PreparedStatement check = conn.prepareStatement("SELECT * FROM users WHERE username = ?");
                    check.setString(1, u);
                    if (check.executeQuery().next()) {
                        System.out.println("!! Register Fail: " + u + " exists.");
                        send(t, "User already exists", 409);
                    } else {
                        System.out.println(">> Register Success: " + u);
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
                        System.out.println(">> Login Success: " + u);
                        lastSeen.put(u, System.currentTimeMillis());
                        send(t, "OK", 200);
                    } else {
                        System.out.println("!! Login Fail: " + u);
                        send(t, "Invalid credentials", 401);
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                send(t, "DB Error: " + e.getMessage(), 500);
            }
        }
    }

    static class FriendHandler implements HttpHandler {
        String mode;
        FriendHandler(String m) { this.mode = m; }
        public void handle(HttpExchange t) throws IOException {
            String body = readBody(t);
            String[] parts = body.split(":");
            String user = parts[0].toLowerCase();
            lastSeen.put(user, System.currentTimeMillis());

            try (Connection conn = connect()) {
                if (mode.equals("add")) {
                    String friend = parts[1].trim().toLowerCase();
                    if (user.equals(friend)) { send(t, "Cannot add self", 400); return; }

                    PreparedStatement checkUser = conn.prepareStatement("SELECT * FROM users WHERE username = ?");
                    checkUser.setString(1, friend);
                    if (!checkUser.executeQuery().next()) {
                        send(t, "User not found", 404); return;
                    }

                    String sql = "INSERT INTO friends (userA, userB) VALUES (?, ?) ON CONFLICT (userA, userB) DO NOTHING";
                    PreparedStatement insert = conn.prepareStatement(sql);
                    
                    insert.setString(1, user); insert.setString(2, friend); insert.executeUpdate();
                    insert.setString(1, friend); insert.setString(2, user); insert.executeUpdate();
                    
                    send(t, "Friend Added!", 200);

                } else if (mode.equals("list")) {
                    PreparedStatement stmt = conn.prepareStatement("SELECT userB FROM friends WHERE userA = ?");
                    stmt.setString(1, user);
                    ResultSet rs = stmt.executeQuery();
                    
                    StringBuilder response = new StringBuilder();
                    long now = System.currentTimeMillis();
                    
                    while (rs.next()) {
                        String f = rs.getString("userB");
                        boolean isOnline = lastSeen.containsKey(f) && (now - lastSeen.get(f) < 5000);
                        response.append(f).append(":").append(isOnline ? "1" : "0").append(",");
                    }
                    send(t, response.toString(), 200);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                send(t, "DB Error: " + e.getMessage(), 500);
            }
        }
    }

    static class ChatHandler implements HttpHandler {
        String mode;
        ChatHandler(String m) { this.mode = m; }
        public void handle(HttpExchange t) throws IOException {
            String body = readBody(t);
            
            try (Connection conn = connect()) {
                if (mode.equals("send")) {
                    String[] parts = body.split(":", 3);
                    if(parts.length < 3) return;
                    
                    String s = parts[0].toLowerCase();
                    String r = parts[1].toLowerCase();
                    String msg = parts[2];
                    
                    PreparedStatement stmt = conn.prepareStatement("INSERT INTO chats(sender, receiver, message, timestamp) VALUES(?,?,?,?)");
                    stmt.setString(1, s);
                    stmt.setString(2, r);
                    stmt.setString(3, msg);
                    stmt.setLong(4, System.currentTimeMillis());
                    stmt.executeUpdate();
                    
                    lastSeen.put(s, System.currentTimeMillis());
                    send(t, "Sent", 200);

                } else if (mode.equals("get")) {
                    String[] parts = body.split(":");
                    String me = parts[0].toLowerCase();
                    String friend = parts[1].toLowerCase();
                    lastSeen.put(me, System.currentTimeMillis());

                    String sql = "SELECT sender, message FROM chats WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?) ORDER BY timestamp ASC";
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, me); stmt.setString(2, friend);
                    stmt.setString(3, friend); stmt.setString(4, me);
                    
                    ResultSet rs = stmt.executeQuery();
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) {
                        sb.append(rs.getString("sender")).append(":").append(rs.getString("message")).append("|");
                    }
                    send(t, sb.toString(), 200);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                send(t, "DB Error: " + e.getMessage(), 500);
            }
        }
    }

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
}

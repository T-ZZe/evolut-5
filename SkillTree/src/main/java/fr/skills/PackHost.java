package fr.skills;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/** Charge le resource pack intégré au jar et l'héberge (mode "builtin"). */
final class PackHost {
    static final String FILE = "SkillTreePack.zip";

    private final SkillsPlugin plugin;
    private HttpServer server;

    byte[] bytes;
    String sha1Hex;
    /** URL envoyée aux joueurs (null = pack indisponible). */
    String url;

    PackHost(SkillsPlugin plugin) {
        this.plugin = plugin;
    }

    boolean start() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", true)) return false;
        try (InputStream in = plugin.getResource(FILE)) {
            if (in == null) {
                plugin.getLogger().warning("Resource pack introuvable dans le jar.");
                return false;
            }
            bytes = in.readAllBytes();
            sha1Hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            // Copie pratique pour l'hébergement externe
            Path out = plugin.getDataFolder().toPath().resolve(FILE);
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
        } catch (Exception e) {
            plugin.getLogger().warning("Lecture du resource pack impossible : " + e.getMessage());
            return false;
        }

        String mode = cfg.getString("resource-pack.mode", "builtin");
        if (mode.equalsIgnoreCase("external")) {
            String u = cfg.getString("resource-pack.external-url", "");
            if (u == null || u.isBlank()) {
                plugin.getLogger().warning("resource-pack.mode=external mais external-url est vide.");
                return false;
            }
            String h = cfg.getString("resource-pack.external-sha1", "");
            if (h != null && !h.isBlank()) sha1Hex = h.trim().toLowerCase();
            url = u.trim();
            plugin.getLogger().info("Resource pack externe : " + url);
            return true;
        }

        String host = cfg.getString("resource-pack.host", "");
        int port = cfg.getInt("resource-pack.port", 8123);
        if (host == null || host.isBlank()) {
            plugin.getLogger().warning("resource-pack.host est vide dans config.yml : "
                    + "renseigne l'IP publique ou le domaine du serveur, sinon les joueurs ne pourront pas télécharger le pack.");
            host = "localhost";
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/" + FILE, this::handle);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "SkillTree-PackHost");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Impossible de démarrer l'hébergement du pack sur le port " + port
                    + " : " + e.getMessage());
            return false;
        }
        url = "http://" + host + ":" + port + "/" + FILE;
        plugin.getLogger().info("Resource pack hébergé sur " + url + " (sha1 " + sha1Hex + ")");
        return true;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            if (method.equals("HEAD")) {
                ex.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}

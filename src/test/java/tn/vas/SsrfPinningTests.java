package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import tn.vas.config.AppConfig;
import tn.vas.security.UrlGuard;

/** Le contrôle d'adresse est fait par le résolveur DNS utilisé pour ouvrir la socket : plus de fenêtre entre « contrôle » et « connexion ». */
class SsrfPinningTests {
    @Test
    void resolverRefusesNonPublicAddressesAtConnectionTime() {
        var r = new UrlGuard(false, false, "").dnsResolver();
        for (String h : new String[]{"localhost", "127.0.0.1", "10.0.0.5", "169.254.169.254", "192.168.1.1", "::1"})
            assertThrows(java.net.UnknownHostException.class, () -> r.resolve(h), h);
    }

    @Test
    void realConnectionToLoopbackIsRefusedUnlessAllowed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> { byte[] b = "ok".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            var strict = new AppConfig().webhookHttp(new UrlGuard(true, false, ""));
            assertThrows(Exception.class, () -> strict.post().uri(url).body("x").retrieve().toBodilessEntity(), "connexion vers loopback refusée");
            var dev = new AppConfig().webhookHttp(new UrlGuard(true, true, ""));
            assertEquals(200, dev.post().uri(url).body("x").retrieve().toBodilessEntity().getStatusCode().value());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/r", ex -> { ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest"); ex.sendResponseHeaders(302, -1); ex.close(); });
        server.start();
        try {
            var dev = new AppConfig().webhookHttp(new UrlGuard(true, true, ""));
            var status = dev.post().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/r").body("x").exchange((req, res) -> res.getStatusCode().value());
            assertEquals(302, status, "la redirection n'est pas suivie");
        } finally {
            server.stop(0);
        }
    }
}

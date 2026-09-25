import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 本地回显上游：把收到的请求头原样回吐成 JSON，供网关转发验证用。
 * 起在 8091。
 */
public class EchoUpstream {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8091), 0);
        server.createContext("/", exchange -> {
            String headers = exchange.getRequestHeaders().entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\":\"" + String.join(",", e.getValue()) + "\"")
                    .collect(Collectors.joining(","));
            String body = "{\"upstream\":\"8091\",\"method\":\"" + exchange.getRequestMethod()
                    + "\",\"path\":\"" + exchange.getRequestURI().getPath()
                    + "\",\"headers\":{" + headers + "}}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        System.out.println("echo-upstream on 8091");
    }
}

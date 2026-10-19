import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

public class Actions {

    public static void register(HttpServer server) {
        server.createContext("/actions", Actions::handle);
    }

    private static void handle(HttpExchange exchange)
            throws IOException {

        try {
            if (exchange.getRequestMethod().equals("POST")) {
                update(exchange);
            } else if (exchange.getRequestMethod().equals("GET")) {
                showPage(exchange);
            } else {
                send(exchange, 405, "Method not allowed.");
            }
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, "Invalid record or action details.");
        } catch (IOException exception) {
            System.err.println(exception.getMessage());
            send(exchange, 500, "Could not process corrective actions.");
        }
    }

    private static void update(HttpExchange exchange)
            throws IOException {

        byte[] body = exchange.getRequestBody().readNBytes(20001);

        if (body.length > 20000) {
            send(exchange, 413, "Action details are too large.");
            return;
        }

        Map<String, String> form = new HashMap<>();

        for (String pair : new String(
                body, StandardCharsets.UTF_8).split("&")) {

            String[] parts = pair.split("=", 2);

            form.put(
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts.length == 2
                    ? URLDecoder.decode(
                        parts[1], StandardCharsets.UTF_8).trim()
                    : ""
            );
        }

        String id = form.getOrDefault("id", "");

        if (!id.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                + "[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException();
        }

        String status = form.getOrDefault("status", "");
        String note = form.getOrDefault("note", "");
        String staff = form.getOrDefault("staff", "");

        if (!Set.of("Open", "In Progress", "Closed").contains(status)
                || note.isBlank() || note.length() > 2000
                || staff.isBlank() || staff.length() > 100) {
            throw new IllegalArgumentException();
        }

        Path file = Path.of("data", id + ".properties");

        if (!Files.isRegularFile(file)) {
            send(exchange, 404, "Record not found.");
            return;
        }

        Properties record = new Properties();

        try (var input = Files.newInputStream(file)) {
            record.load(input);
        }

        record.setProperty("actionStatus", status);
        record.setProperty("actionNote", note);
        record.setProperty("actionBy", staff);
        record.setProperty(
            "actionUpdatedAt", LocalDateTime.now().toString()
        );

        Path temporary = Files.createTempFile(
            Path.of("data"), "action-", ".tmp"
        );

        try {
            try (var output = Files.newOutputStream(temporary)) {
                record.store(output, "FuelCheck test record");
            }

            Files.move(temporary, file,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }

        exchange.getResponseHeaders().set("Location", "/actions?saved=1");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void showPage(HttpExchange exchange)
            throws IOException {

        StringBuilder cards = new StringBuilder();

        try (var files = Files.list(Path.of("data"))) {
            for (Path file : files
                    .filter(p -> p.toString().endsWith(".properties"))
                    .sorted().toList()) {

                Properties record = new Properties();

                try (var input = Files.newInputStream(file)) {
                    record.load(input);
                }

                String status = record.getProperty(
                    "actionStatus", "Open"
                );

                cards.append("<section><h2>")
                    .append(html(record.getProperty("tank", "")))
                    .append(" — ")
                    .append(html(record.getProperty("parameter", "")))
                    .append("</h2><p>Test date: ")
                    .append(html(record.getProperty("date", "")))
                    .append("</p><p>Original review result: <strong>")
                    .append(html(record.getProperty("reviewStatus", "")))
                    .append("</strong></p><form method='post' action='/actions'>")
                    .append("<input type='hidden' name='id' value='")
                    .append(html(record.getProperty("id", "")))
                    .append("'><label>Action status<select name='status'>");

                for (String option :
                        List.of("Open", "In Progress", "Closed")) {
                    cards.append("<option")
                        .append(option.equals(status) ? " selected" : "")
                        .append(">").append(option).append("</option>");
                }

                cards.append("</select></label>")
                    .append("<label>Action note")
                    .append("<textarea name='note' maxlength='2000' required>")
                    .append(html(record.getProperty("actionNote", "")))
                    .append("</textarea></label>")
                    .append("<label>Updated by")
                    .append("<input name='staff' maxlength='100' required value='")
                    .append(html(record.getProperty("actionBy", "")))
                    .append("'></label><button>Save Action</button></form>")
                    .append("<p>Last updated: ")
                    .append(html(record.getProperty("actionUpdatedAt", "—")))
                    .append("</p></section>");
            }
        }

        if (cards.isEmpty()) {
            cards.append("<section>No test records yet.</section>");
        }

        String notice = "saved=1".equals(
            exchange.getRequestURI().getQuery())
            ? "<p role='status'>Corrective action saved successfully.</p>"
            : "";

        String page = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport"
                      content="width=device-width, initial-scale=1">
                <title>FuelCheck Actions</title>
                <style>
                    body { font-family:Arial; background:#f1f5f9;
                           color:#172033; margin:0; }
                    header { background:#123b36; color:white; padding:24px; }
                    main { max-width:900px; margin:auto; padding:24px; }
                    section { background:white; padding:24px;
                              margin:20px 0; border-radius:12px; }
                    a { color:#16735d; }
                    label { display:block; margin:14px 0; }
                    input,select,textarea { display:block; width:100%;
                        box-sizing:border-box; padding:10px; margin-top:8px;
                        border:1px solid #cbd5e1; border-radius:6px; }
                    textarea { min-height:90px; }
                    button { background:#16735d; color:white;
                        padding:12px 20px; border:0; border-radius:6px; }
                </style>
            </head>
            <body>
                <header><h1>Corrective Actions</h1></header>
                <main>
                    <a href="/">Add Test</a> |
                    <a href="/history">Reading History</a>
                    <p>Record follow-up work for a test.
                    Closing an action preserves the original test result.</p>
            """;

        send(exchange, 200,
            page + notice + cards + "</main></body></html>");
    }

    private static String html(String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }

    private static void send(
            HttpExchange exchange, int status, String text)
            throws IOException {

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
            "Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

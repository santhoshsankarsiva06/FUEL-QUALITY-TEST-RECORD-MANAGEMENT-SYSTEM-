import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Reports {
    private static final int MAX_SIZE = 5 * 1024 * 1024;

    public static void register(HttpServer server) throws IOException {
        Files.createDirectories(Path.of("uploads"));

        server.createContext("/reports", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();

                if (path.equals("/reports/upload")
                        && exchange.getRequestMethod().equals("POST")) {
                    upload(exchange);

                } else if (path.equals("/reports/file")
                        && exchange.getRequestMethod().equals("GET")) {
                    download(exchange);

                } else if (path.equals("/reports")
                        && exchange.getRequestMethod().equals("GET")) {
                    showPage(exchange);

                } else {
                    text(exchange, 404, "Page or method not supported.");
                }
            } catch (IllegalArgumentException exception) {
                text(exchange, 400, "Invalid report details.");
            } catch (IOException exception) {
                System.err.println(exception.getMessage());
                text(exchange, 500, "Report operation failed.");
            }
        });
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();

        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);

                values.put(parts[0], parts.length == 2
                    ? java.net.URLDecoder.decode(
                        parts[1], StandardCharsets.UTF_8)
                    : "");
            }
        }
        return values;
    }

    private static void upload(HttpExchange exchange)
            throws IOException {

        Map<String, String> params = query(exchange);
        String id = params.getOrDefault("id", "");
        String type = params.getOrDefault("type", "");

        if (!validId(id)
                || !Files.isRegularFile(
                    Path.of("data", id + ".properties"))) {
            text(exchange, 400, "Select an existing test record.");
            return;
        }

        if (!Set.of("pdf", "jpg", "png").contains(type)) {
            text(exchange, 400, "Choose a PDF, JPG, or PNG.");
            return;
        }

        byte[] bytes = exchange.getRequestBody()
            .readNBytes(MAX_SIZE + 1);

        if (bytes.length == 0 || bytes.length > MAX_SIZE) {
            text(exchange, 400, "Choose a non-empty file up to 5 MB.");
            return;
        }

        boolean matches = switch (type) {
            case "pdf" -> bytes.length >= 5
                && new String(bytes, 0, 5, StandardCharsets.US_ASCII)
                    .equals("%PDF-");

            case "jpg" -> bytes.length >= 3
                && (bytes[0] & 255) == 255
                && (bytes[1] & 255) == 216
                && (bytes[2] & 255) == 255;

            case "png" -> bytes.length >= 8
                && Arrays.equals(Arrays.copyOf(bytes, 8),
                    new byte[] {
                        (byte)137, 80, 78, 71, 13, 10, 26, 10
                    });

            default -> false;
        };

        if (!matches) {
            text(exchange, 400,
                "File content does not match its selected format.");
            return;
        }

        String name = id + "_" + UUID.randomUUID() + "." + type;

        Files.write(
            Path.of("uploads", name),
            bytes,
            StandardOpenOption.CREATE_NEW
        );

        text(exchange, 201, "Report uploaded successfully.");
    }

    private static void download(HttpExchange exchange)
            throws IOException {

        String name = query(exchange).getOrDefault("name", "");

        if (!name.matches(
                "[0-9a-f-]{36}_[0-9a-f-]{36}[.](pdf|jpg|png)")) {
            text(exchange, 400, "Invalid report name.");
            return;
        }

        Path file = Path.of("uploads", name);

        if (!Files.isRegularFile(file)) {
            text(exchange, 404, "Report not found.");
            return;
        }

        exchange.getResponseHeaders().set(
            "Content-Disposition",
            "attachment; filename=\"" + name + "\""
        );
        exchange.getResponseHeaders().set(
            "X-Content-Type-Options", "nosniff"
        );

        send(exchange, 200, "application/octet-stream",
            Files.readAllBytes(file));
    }

    private static void showPage(HttpExchange exchange)
            throws IOException {

        StringBuilder options = new StringBuilder();
        StringBuilder links = new StringBuilder();

        List<Path> reports;
        try (var files = Files.list(Path.of("uploads"))) {
            reports = files.filter(Files::isRegularFile)
                .sorted().toList();
        }

        try (var files = Files.list(Path.of("data"))) {
            for (Path file : files
                    .filter(p -> p.toString().endsWith(".properties"))
                    .sorted().toList()) {

                Properties record = new Properties();
                try (var input = Files.newInputStream(file)) {
                    record.load(input);
                }

                String id = record.getProperty("id", "");
                if (!validId(id)) {
                    continue;
                }

                String label = record.getProperty("tank", "")
                    + " | " + record.getProperty("date", "")
                    + " | " + record.getProperty("parameter", "");

                options.append("<option value='")
                    .append(id).append("'>")
                    .append(html(label)).append("</option>");

                for (Path report : reports) {
                    String name = report.getFileName().toString();

                    if (name.startsWith(id + "_")
                            && name.matches(
                                "[0-9a-f-]{36}_[0-9a-f-]{36}"
                                + "[.](pdf|jpg|png)")) {
                        links.append("<li>")
                            .append(html(label))
                            .append(" — <a href='/reports/file?name=")
                            .append(name)
                            .append("'>Download ")
                            .append(name.substring(name.lastIndexOf('.') + 1))
                            .append(" report</a></li>");
                    }
                }
            }
        }

        if (links.isEmpty()) {
            links.append("<li>No reports uploaded yet.</li>");
        }

        String page = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport"
                      content="width=device-width, initial-scale=1">
                <title>FuelCheck Reports</title>
                <style>
                    body { font-family:Arial; margin:0;
                        background:#f1f5f9; color:#172033; }
                    header { padding:24px; background:#123b36;
                        color:white; }
                    main { max-width:900px; margin:auto; padding:24px; }
                    section { background:white; padding:24px;
                        border-radius:12px; margin:20px 0; }
                    label { display:block; margin:16px 0; }
                    select,input { display:block; padding:10px;
                        margin-top:8px; max-width:100%; }
                    button { padding:12px 20px; background:#16735d;
                        color:white; border:0; border-radius:6px; }
                    a { color:#16735d; }
                    li { margin:16px 0; overflow-wrap:anywhere; }
                </style>
            </head>
            <body>
                <header><h1>Instrument Reports</h1></header>
                <main>
                    <a href="/dashboard">Dashboard</a> |
                    <a href="/history">Reading History</a>
                    <section>
                        <h2>Attach Report to a Saved Test</h2>
                        <p>PDF, JPG, or PNG. Maximum size: 5 MB.</p>
                        <form id="uploadForm">
                            <label>Test Record
                                <select id="record" required>
                                    <option value="">Select a test</option>
                                    RECORD_OPTIONS
                                </select>
                            </label>
                            <label>Report File
                                <input id="report" type="file"
                                    accept=".pdf,.jpg,.jpeg,.png" required>
                            </label>
                            <button id="uploadButton">Upload Report</button>
                            <p id="message" role="status"></p>
                        </form>
                    </section>
                    <section>
                        <h2>Saved Reports</h2>
                        <ul>REPORT_LINKS</ul>
                    </section>
                </main>
                <script>
                    document.getElementById("uploadForm")
                        .addEventListener("submit", async event => {
                            event.preventDefault();

                            const file = document.getElementById("report")
                                .files[0];
                            const id = document.getElementById("record").value;
                            const message = document.getElementById("message");
                            const button = document.getElementById("uploadButton");

                            let type = file.name.split(".").pop().toLowerCase();
                            if (type === "jpeg") type = "jpg";

                            if (!["pdf", "jpg", "png"].includes(type)
                                    || file.size === 0
                                    || file.size > 5 * 1024 * 1024) {
                                message.textContent =
                                    "Choose a PDF, JPG, or PNG up to 5 MB.";
                                return;
                            }

                            button.disabled = true;
                            message.textContent = "Uploading...";

                            try {
                                const params = new URLSearchParams({id, type});
                                const response = await fetch(
                                    "/reports/upload?" + params,
                                    {method:"POST", body:file}
                                );

                                message.textContent = await response.text();

                                if (response.ok) {
                                    alert("Report uploaded successfully.");
                                    location.reload();
                                }
                            } catch (error) {
                                message.textContent =
                                    "Connection failed. Check the Java server.";
                            } finally {
                                button.disabled = false;
                            }
                        });
                </script>
            </body>
            </html>
            """;

        page = page.replace("RECORD_OPTIONS", options.toString())
                   .replace("REPORT_LINKS", links.toString());

        send(exchange, 200, "text/html; charset=UTF-8",
            page.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean validId(String id) {
        return id.matches(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    private static String html(String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }

    private static void text(
            HttpExchange exchange, int status, String message)
            throws IOException {
        send(exchange, status, "text/plain; charset=UTF-8",
            message.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(
            HttpExchange exchange, int status,
            String type, byte[] content) throws IOException {

        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);

        try (var output = exchange.getResponseBody()) {
            output.write(content);
        }
    }
}
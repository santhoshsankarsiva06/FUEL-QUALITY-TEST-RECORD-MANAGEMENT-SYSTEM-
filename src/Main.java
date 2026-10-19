import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class Main {

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("data"));

        HttpServer server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 8080), 0
        );

        server.createContext("/save", Main::saveRecord);
        server.createContext("/history", Main::showHistory);
        Actions.register(server);
        Dashboard.register(server);
        Export.register(server);
        Reports.register(server);

        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 405, "Method not allowed.");
                return;
            }

            if (!exchange.getRequestURI().getPath().equals("/")) {
                reply(exchange, 404, "Page not found.");
                return;
            }

            Path file = Path.of("web", "index.html");

            if (!Files.exists(file)) {
                reply(exchange, 404, "web/index.html was not found.");
                return;
            }

            send(exchange, 200, "text/html; charset=UTF-8",
                Files.readAllBytes(file));
        });

        server.start();
        System.out.println("FuelCheck: http://localhost:8080");
    }

    private static void saveRecord(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("POST")) {
            reply(exchange, 405, "Use POST to save a record.");
            return;
        }

        try {
            byte[] body = exchange.getRequestBody().readNBytes(20001);

            if (body.length > 20000) {
                reply(exchange, 413, "Form is too large.");
                return;
            }

            Map<String, String> form = parseForm(
                new String(body, StandardCharsets.UTF_8)
            );

            String[] required = {
                "tank", "fuel", "date", "staff",
                "method", "parameter", "value", "unit"
            };

            for (String field : required) {
                if (form.getOrDefault(field, "").isBlank()) {
                    throw new IllegalArgumentException();
                }
            }

            LocalDateTime.parse(form.get("date"));

            BigDecimal value = new BigDecimal(form.get("value"));

            if (value.signum() < 0) {
                throw new IllegalArgumentException();
            }

            String status = "Not evaluated";
            String limitText = form.getOrDefault("limit", "");

            if (!limitText.isBlank()) {
                BigDecimal limit = new BigDecimal(limitText);

                if (limit.signum() < 0) {
                    throw new IllegalArgumentException();
                }

                status = value.compareTo(limit) > 0
                    ? "Review required"
                    : "Within configured limit";
            }

            Properties record = new Properties();

            String[] fields = {
                "tank", "fuel", "date", "staff", "method",
                "parameter", "value", "unit", "limit",
                "reference", "notes"
            };

            for (String field : fields) {
                record.setProperty(
                    field, form.getOrDefault(field, "")
                );
            }

            String id = UUID.randomUUID().toString();

            record.setProperty("id", id);
            record.setProperty("reviewStatus", status);
            record.setProperty(
                "createdAt", LocalDateTime.now().toString()
            );

            try (OutputStream output = Files.newOutputStream(
                    Path.of("data", id + ".properties"))) {
                record.store(output, "FuelCheck test record");
            }

            reply(exchange, 201,
                "Record saved successfully! Result: " + status);

        } catch (IllegalArgumentException exception) {
            reply(exchange, 400,
                "Check required fields, numbers, and date/time.");

        } catch (IOException exception) {
            System.err.println(exception.getMessage());
            reply(exchange, 500, "Could not save the record.");
        }
    }

    private static void showHistory(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equals("GET")) {
            reply(exchange, 405, "Method not allowed.");
            return;
        }

        try {
            StringBuilder rows = new StringBuilder();
            int count = 0;

            try (var files = Files.list(Path.of("data"))) {
                var paths = files
                    .filter(path -> path.getFileName().toString()
                        .endsWith(".properties"))
                    .sorted()
                    .toList();

                for (Path file : paths) {
                    Properties record = new Properties();

                    try (var input = Files.newInputStream(file)) {
                        record.load(input);
                    }

                    rows.append("<tr>");

                    String[] fields = {
                        "date", "tank", "fuel", "parameter",
                        "method", "value", "unit", "limit",
                        "reference", "staff", "reviewStatus", "notes"
                    };

                    for (String field : fields) {
                        rows.append("<td>")
                            .append(escapeHtml(
                                record.getProperty(field, "")
                            ))
                            .append("</td>");
                    }

                    rows.append("</tr>");
                    count++;
                }
            }

            if (count == 0) {
                rows.append(
                    "<tr><td colspan=\"12\">No saved records.</td></tr>"
                );
            }

            String page = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport"
                          content="width=device-width, initial-scale=1">
                    <title>FuelCheck History</title>
                    <style>
                        body {
                            margin: 0;
                            font-family: Arial, sans-serif;
                            background: #f1f5f9;
                            color: #172033;
                        }
                        header {
                            background: #123b36;
                            color: white;
                            padding: 28px;
                        }
                        main { padding: 24px; }
                        a {
                            color: #16735d;
                            font-weight: bold;
                        }
                        .table-wrap {
                            overflow-x: auto;
                            background: white;
                            margin-top: 20px;
                            border-radius: 10px;
                        }
                        table {
                            width: 100%;
                            border-collapse: collapse;
                        }
                        th, td {
                            padding: 14px;
                            text-align: left;
                            vertical-align: top;
                            border-bottom: 1px solid #ddd;
                        }
                        th {
                            background: #e4f0eb;
                            white-space: nowrap;
                        }
                        td {
                            min-width: 100px;
                            white-space: pre-wrap;
                            overflow-wrap: anywhere;
                        }
                    </style>
                </head>
                <body>
                    <header>
                        <h1>FuelCheck</h1>
                        <p>Reading History</p>
                    </header>
                    <main>
                        <a href="/">Back to Add Quality Test</a>
                        <p>Total saved records: RECORD_COUNT</p>
                        <div style="display:flex;gap:12px;flex-wrap:wrap;margin:20px 0;">
    <label>
        Tank
        <input id="tankFilter" placeholder="Example: TANK-01">
    </label>

    <label>
        Fuel
        <select id="fuelFilter">
            <option value="">All fuels</option>
            <option>Petrol E10</option>
            <option>Petrol E20</option>
            <option>Diesel</option>
            <option>Other</option>
        </select>
    </label>

    <label>
        Test Date
        <input id="dateFilter" type="date">
    </label>

    <label>
        Review Status
        <select id="statusFilter">
            <option value="">All results</option>
            <option>Review required</option>
            <option>Within configured limit</option>
            <option>Not evaluated</option>
        </select>
    </label>

    <button type="button" onclick="clearFilters()">
        Clear Filters
    </button>
</div>

<p id="filterCount" role="status"></p>
                        <div class="table-wrap">
                            <table>
                                <thead>
                                    <tr>
                                        <th>Test Date</th>
                                        <th>Tank</th>
                                        <th>Fuel</th>
                                        <th>Parameter</th>
                                        <th>Method</th>
                                        <th>Value</th>
                                        <th>Unit</th>
                                        <th>Maximum Limit</th>
                                        <th>Limit Reference</th>
                                        <th>Tested By</th>
                                        <th>Review Status</th>
                                        <th>Notes</th>
                                    </tr>
                                </thead>
                                <tbody>RECORD_ROWS</tbody>
                            </table>
                        </div>
                    </main>
                    <script>
    const filterIds = [
        "tankFilter", "fuelFilter", "dateFilter", "statusFilter"
    ];

    function applyFilters() {
        const tank = document.getElementById("tankFilter")
            .value.trim().toLowerCase();

        const fuel = document.getElementById("fuelFilter").value;
        const date = document.getElementById("dateFilter").value;
        const status = document.getElementById("statusFilter").value;

        let visible = 0;

        document.querySelectorAll("tbody tr").forEach(row => {
            const cells = row.querySelectorAll("td");

            if (cells.length !== 12) {
                return;
            }

            const matches =
                cells[1].textContent.toLowerCase().includes(tank) &&
                (!fuel || cells[2].textContent === fuel) &&
                (!date || cells[0].textContent.slice(0, 10) === date) &&
                (!status || cells[10].textContent === status);

            row.hidden = !matches;

            if (matches) {
                visible++;
            }
        });

        document.getElementById("filterCount").textContent =
            "Matching records: " + visible;
    }

    function clearFilters() {
        filterIds.forEach(id => {
            document.getElementById(id).value = "";
        });

        applyFilters();
    }

    filterIds.forEach(id => {
        document.getElementById(id)
            .addEventListener("input", applyFilters);
    });

    applyFilters();
</script>
                </body>
                </html>
                """;

            page = page.replace("RECORD_COUNT", String.valueOf(count))
                       .replace("RECORD_ROWS", rows.toString());

            send(exchange, 200, "text/html; charset=UTF-8",
                page.getBytes(StandardCharsets.UTF_8));

        } catch (IOException exception) {
            System.err.println(exception.getMessage());
            reply(exchange, 500, "Could not load saved records.");
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();

        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);

            String key = URLDecoder.decode(
                parts[0], StandardCharsets.UTF_8
            );

            String value = parts.length == 2
                ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                : "";

            form.put(key, value.trim());
        }

        return form;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }

    private static void reply(
            HttpExchange exchange, int status, String message)
            throws IOException {

        send(exchange, status, "text/plain; charset=UTF-8",
            message.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(
            HttpExchange exchange, int status,
            String contentType, byte[] content)
            throws IOException {

        exchange.getResponseHeaders().set(
            "Content-Type", contentType
        );
        exchange.getResponseHeaders().set(
            "Cache-Control", "no-store"
        );

        exchange.sendResponseHeaders(status, content.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }
}

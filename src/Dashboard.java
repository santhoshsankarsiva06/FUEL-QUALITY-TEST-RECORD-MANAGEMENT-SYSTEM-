import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Dashboard {

    public static void register(HttpServer server) {
        server.createContext("/dashboard", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            int total = 0;
            int flagged = 0;
            int pending = 0;
            int closed = 0;

            try {
                try (var files = Files.list(Path.of("data"))) {
                    var records = files
                        .filter(path -> path.toString()
                            .endsWith(".properties"))
                        .toList();

                    for (Path file : records) {
                        Properties record = new Properties();

                        try (var input = Files.newInputStream(file)) {
                            record.load(input);
                        }

                        total++;

                        if ("Review required".equals(
                                record.getProperty("reviewStatus"))) {
                            flagged++;
                        }

                        String status = record.getProperty(
                            "actionStatus", ""
                        );

                        if ("Closed".equals(status)) {
                            closed++;
                        } else if ("Open".equals(status)
                                || "In Progress".equals(status)
                                || (status.isBlank()
                                    && "Review required".equals(
                                        record.getProperty(
                                            "reviewStatus")))) {
                            pending++;
                        }
                    }
                }

                String page = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport"
                              content="width=device-width, initial-scale=1">
                        <title>FuelCheck Dashboard</title>
                        <style>
                            * { box-sizing: border-box; }
                            body {
                                margin: 0;
                                font-family: Arial, sans-serif;
                                background: #f1f5f9;
                                color: #172033;
                            }
                            header {
                                padding: 30px;
                                background: #123b36;
                                color: white;
                            }
                            main {
                                max-width: 1100px;
                                margin: auto;
                                padding: 24px;
                            }
                            nav {
                                display: flex;
                                gap: 18px;
                                flex-wrap: wrap;
                                margin-bottom: 24px;
                            }
                            a {
                                color: #16735d;
                                font-weight: bold;
                            }
                            .grid {
                                display: grid;
                                grid-template-columns:
                                    repeat(auto-fit, minmax(200px, 1fr));
                                gap: 18px;
                            }
                            .card {
                                background: white;
                                padding: 24px;
                                border-radius: 12px;
                                border-top: 5px solid #16735d;
                            }
                            .number {
                                font-size: 42px;
                                font-weight: bold;
                                margin: 12px 0;
                            }
                            .note {
                                margin-top: 24px;
                                line-height: 1.6;
                            }
                        </style>
                    </head>
                    <body>
                        <header>
                            <h1>FuelCheck Dashboard</h1>
                            <p>Fuel Quality Test Record Management</p>
                        </header>
                        <main>
                            <nav>
                                <a href="/">Add Test</a>
                                <a href="/history">Reading History</a>
                                <a href="/actions">Corrective Actions</a>
                                <a href="/export">Download CSV</a>
                                <a href="/reports">Instrument Reports</a>
                            </nav>

                            <div class="grid">
                                <div class="card">
                                    <h2>Total Tests</h2>
                                    <p class="number">TOTAL_COUNT</p>
                                </div>

                                <div class="card">
                                    <h2>Flagged Results</h2>
                                    <p class="number">FLAGGED_COUNT</p>
                                </div>

                                <div class="card">
                                    <h2>Pending Actions</h2>
                                    <p class="number">PENDING_COUNT</p>
                                </div>

                                <div class="card">
                                    <h2>Closed Actions</h2>
                                    <p class="number">CLOSED_COUNT</p>
                                </div>
                            </div>

                            <div class="note">
                                <p>Flagged results are tests that exceeded
                                their entered maximum limit.</p>
                                <p>Pending actions include flagged tests
                                awaiting follow-up and actions marked Open
                                or In Progress. Closing an action preserves
                                the original flagged result.</p>
                                <p>Refresh this page after saving a test
                                or updating an action.</p>
                            </div>
                        </main>
                    </body>
                    </html>
                    """;

                page = page
                    .replace("TOTAL_COUNT", String.valueOf(total))
                    .replace("FLAGGED_COUNT", String.valueOf(flagged))
                    .replace("PENDING_COUNT", String.valueOf(pending))
                    .replace("CLOSED_COUNT", String.valueOf(closed));

                byte[] content = page.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set(
                    "Content-Type", "text/html; charset=UTF-8"
                );
                exchange.getResponseHeaders().set(
                    "Cache-Control", "no-store"
                );

                exchange.sendResponseHeaders(200, content.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(content);
                }

            } catch (java.io.IOException exception) {
                System.err.println(exception.getMessage());

                byte[] message = "Could not load dashboard."
                    .getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(500, message.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(message);
                }
            }
        });
    }
}

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Export {

    public static void register(HttpServer server) {
        server.createContext("/export", exchange -> {

            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            byte[] content;

            try {
                String[] fields = {
                    "id", "date", "tank", "fuel", "parameter",
                    "method", "value", "unit", "limit",
                    "reference", "staff", "reviewStatus", "notes",
                    "actionStatus", "actionNote", "actionBy",
                    "actionUpdatedAt"
                };

                String[] headings = {
                    "Record ID", "Test Date", "Tank", "Fuel",
                    "Parameter", "Method", "Value", "Unit",
                    "Maximum Limit", "Limit Reference",
                    "Tested By", "Review Status", "Notes",
                    "Action Status", "Action Note",
                    "Action Updated By", "Action Updated At"
                };

                // UTF-8 marker helps Excel display text correctly.
                StringBuilder csv = new StringBuilder("\uFEFF");

                appendRow(csv, headings);

                try (var files = Files.list(Path.of("data"))) {
                    var paths = files
                        .filter(path -> path.toString()
                            .endsWith(".properties"))
                        .sorted()
                        .toList();

                    for (Path file : paths) {
                        Properties record = new Properties();

                        try (var input = Files.newInputStream(file)) {
                            record.load(input);
                        }

                        String[] values = new String[fields.length];

                        for (int i = 0; i < fields.length; i++) {
                            values[i] = record.getProperty(fields[i], "");
                        }

                        appendRow(csv, values);
                    }
                }

                content = csv.toString()
                    .getBytes(StandardCharsets.UTF_8);

            } catch (IOException exception) {
                System.err.println(exception.getMessage());

                byte[] message = "Could not export records."
                    .getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set(
                    "Content-Type", "text/plain; charset=UTF-8"
                );
                exchange.sendResponseHeaders(500, message.length);

                try (var output = exchange.getResponseBody()) {
                    output.write(message);
                }
                return;
            }

            exchange.getResponseHeaders().set(
                "Content-Type", "text/csv; charset=UTF-8"
            );

            exchange.getResponseHeaders().set(
                "Content-Disposition",
                "attachment; filename=\"FuelCheck_Records.csv\""
            );

            exchange.getResponseHeaders().set(
                "Cache-Control", "no-store"
            );

            exchange.sendResponseHeaders(200, content.length);

            try (var output = exchange.getResponseBody()) {
                output.write(content);
            }
        });
    }

    private static void appendRow(
            StringBuilder csv, String[] values) {

        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(",");
            }

            csv.append(csvCell(values[i]));
        }

        csv.append("\r\n");
    }

    private static String csvCell(String value) {
        // Prevent entered text being interpreted as an Excel formula.
        String trimmed = value.stripLeading();

        if (!trimmed.isEmpty()
                && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
            value = "'" + value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

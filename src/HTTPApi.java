import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HTTPApi {
    // Socrata / SODA3 settings
    private static final String SODA_BASE = "https://data.cityofchicago.org/resource/"; // use resource endpoint
    private static final String DATASET_ID = "ijzp-q8t2"; // Crimes - 2001 to Present
    // Use the provided Socrata application token (put here without leading $)
    // This reduces rate-limiting and is required for larger / frequent queries.
    private static final String API_KEY = "OVFVq7FFu2FgbGF6gI1Is76IN";
    // (Optional) API Key ID provided for bookkeeping: d3owo6obvwvr5yhyhim7gfbty
    private static final String API_KEY_ID = "d3owo6obvwvr5yhyhim7gfbty";

    private final HttpClient httpClient;

    public HTTPApi() {
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Fetch top N crime types by count from the City of Chicago SODA API.
     * Returns a formatted string listing primary_type and count.
     */
    public String fetchTopCrimes(int limit) throws Exception {
    String selectValue = java.net.URLEncoder.encode("primary_type,COUNT(*) AS cnt", java.nio.charset.StandardCharsets.UTF_8);
    String groupValue = java.net.URLEncoder.encode("primary_type", java.nio.charset.StandardCharsets.UTF_8);
    String orderValue = java.net.URLEncoder.encode("cnt DESC", java.nio.charset.StandardCharsets.UTF_8);
    // filter to crimes in year 2024 (inclusive). Adjust timestamps if you need timezone precision.
    String whereRaw = "date between '2024-01-01T00:00:00' and '2024-12-31T23:59:59'";
    String whereValue = java.net.URLEncoder.encode(whereRaw, java.nio.charset.StandardCharsets.UTF_8);
    String query = String.format("%s%s.json?$select=%s&$group=%s&$order=%s&$limit=%d&$where=%s",
        SODA_BASE, DATASET_ID, selectValue, groupValue, orderValue, limit, whereValue);

    HttpRequest.Builder rb = HttpRequest.newBuilder()
        .uri(new java.net.URI(query))
        .GET()
        // request minimal content (we already select only needed fields)
        .header("Accept", "application/json")
        // ask for gzip to reduce transfer size when supported by server
        .header("Accept-Encoding", "gzip")
        .header("User-Agent", "JavaHttpClient/1.0");

        if (API_KEY != null && !API_KEY.isEmpty()) {
            rb.header("X-App-Token", API_KEY);
        }

        if (API_KEY != null && !API_KEY.isEmpty()) {
            rb.header("X-App-Token", API_KEY);
            // optional bookkeeping header (not required by Socrata, but useful internally)
            rb.header("X-API-Key-Id", API_KEY_ID);
        }

        HttpRequest request = rb.build();

        // measure download duration (includes network + transfer + body consumption)
        long start = System.nanoTime();
        HttpResponse<byte[]> responseBytes = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        long end = System.nanoTime();
        long durationMs = (end - start) / 1_000_000;

        // handle gzip-decoding if needed
        String bodyString;
        java.util.Optional<String> enc = responseBytes.headers().firstValue("Content-Encoding");
        byte[] raw = responseBytes.body();
        if (enc.isPresent() && enc.get().equalsIgnoreCase("gzip")) {
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(raw);
                 java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(bais);
                 java.io.InputStreamReader isr = new java.io.InputStreamReader(gzis, java.nio.charset.StandardCharsets.UTF_8);
                 java.io.BufferedReader br = new java.io.BufferedReader(isr)) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                bodyString = sb.toString();
            }
        } else {
            bodyString = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
        int status = responseBytes.statusCode();
        if (status / 100 != 2) {
            throw new RuntimeException("Unexpected response code: " + status + "\nBody: " + bodyString);
        }

        List<Map.Entry<String, Integer>> parsed = parseTopCrimes(bodyString);
        String formatted = formatTopCrimes(parsed);
        // If parsing produced no results, include a short snippet of the raw response to help debugging
        if (parsed.isEmpty()) {
            String body = bodyString == null ? "" : bodyString;
            String snippet = body.length() > 500 ? body.substring(0, 500) + "..." : body;
            formatted += "\n(Parsing produced 0 items — raw response snippet below)\n" + snippet + "\n";
        }
        formatted += String.format("\nDownload time: %d ms\n", durationMs);
        return formatted;
    }

    // Parse a Socrata response like: [{"primary_type":"THEFT","cnt":"12345"}, ...]
    private List<Map.Entry<String, Integer>> parseTopCrimes(String json) {
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;

        Pattern p = Pattern.compile("\"primary_type\"\\s*:\\s*\"([^\"]+)\".*?\"cnt\"\\s*:\\s*\"?(\\d+)\"?",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(json);
        while (m.find()) {
            String type = m.group(1);
            int cnt = Integer.parseInt(m.group(2));
            out.add(new AbstractMap.SimpleEntry<>(type, cnt));
        }
        return out;
    }

    private String formatTopCrimes(List<Map.Entry<String, Integer>> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("Top ").append(list.size()).append(" crime types in Chicago:\n");
        int i = 1;
        for (Map.Entry<String, Integer> e : list) {
            sb.append(String.format("%2d. %-30s %6d\n", i++, e.getKey(), e.getValue()));
        }
        return sb.toString();
    }
}

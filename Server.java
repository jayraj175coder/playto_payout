import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Server {
    private static final int PORT = 8000;
    private static final int MAX_WEIGHT = 50;
    private static final int MAX_VOLUME = 50;
    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/optimize", new OptimizeHandler());
        server.createContext("/", new StaticFileHandler());
        server.setExecutor(null);

        System.out.println("Smart E-commerce Order Bundling & Shipping Optimization System");
        System.out.println("Server running at http://localhost:" + PORT);
        server.start();
    }

    private static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            String fileName = "/".equals(requestPath)
                ? "index.html"
                : requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;

            Path file = ROOT.resolve(fileName).normalize();
            if (!file.startsWith(ROOT) || !Files.exists(file) || Files.isDirectory(file)) {
                sendText(exchange, 404, "File not found", "text/plain; charset=utf-8");
                return;
            }

            byte[] data = Files.readAllBytes(file);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", detectContentType(fileName));
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private static class OptimizeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Allow-Methods", "POST, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                List<Order> orders = parseOrders(readRequestBody(exchange.getRequestBody()));
                if (orders.isEmpty()) {
                    sendJson(exchange, 400, "{\"error\":\"No valid orders provided\"}");
                    return;
                }

                sendJson(exchange, 200, optimizeOrders(orders).toJson());
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            } catch (Exception ex) {
                sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
            }
        }
    }

    private static OptimizationResult optimizeOrders(List<Order> inputOrders) {
        List<Order> orders = new ArrayList<>(inputOrders);
        orders.sort(
            Comparator.comparingInt(Order::priorityValue).reversed()
                .thenComparingInt(Order::cost).reversed()
                .thenComparingInt(Order::shippingFootprint)
                .thenComparing(Order::zone)
                .thenComparing(Order::id)
        );

        List<Container> containers = new ArrayList<>();
        for (Order order : orders) {
            Container bestContainer = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (Container container : containers) {
                if (!container.canFit(order)) {
                    continue;
                }
                double score = calculatePlacementScore(container, order);
                if (score > bestScore) {
                    bestScore = score;
                    bestContainer = container;
                }
            }

            if (bestContainer == null) {
                bestContainer = new Container(containers.size() + 1);
                containers.add(bestContainer);
            }
            bestContainer.add(order);
        }

        return new OptimizationResult(containers);
    }

    private static double calculatePlacementScore(Container container, Order order) {
        int projectedWeight = container.currentWeight + order.weight;
        int projectedVolume = container.currentVolume + order.volume;
        double weightFill = projectedWeight / (double) MAX_WEIGHT;
        double volumeFill = projectedVolume / (double) MAX_VOLUME;
        double balancePenalty = Math.abs(weightFill - volumeFill);
        double zoneBonus = container.zoneCounts.getOrDefault(order.zone, 0) > 0 ? 0.15 : 0.0;
        double priorityBonus = order.priorityValue() * 0.05;

        return (weightFill + volumeFill) - balancePenalty + zoneBonus + priorityBonus;
    }

    private static List<Order> parseOrders(String requestBody) {
        String trimmed = requestBody == null ? "" : requestBody.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        return (trimmed.startsWith("{") || trimmed.startsWith("["))
            ? parseJsonOrders(trimmed)
            : parseTextOrders(trimmed);
    }

    @SuppressWarnings("unchecked")
    private static List<Order> parseJsonOrders(String requestBody) {
        Object parsed = new JsonParser(requestBody).parse();
        List<Object> rawOrders;

        if (parsed instanceof Map<?, ?> parsedMap) {
            Object ordersNode = parsedMap.get("orders");
            if (!(ordersNode instanceof List<?> list)) {
                throw new IllegalArgumentException("JSON must contain an 'orders' array.");
            }
            rawOrders = (List<Object>) list;
        } else if (parsed instanceof List<?> list) {
            rawOrders = (List<Object>) list;
        } else {
            throw new IllegalArgumentException("Unsupported JSON payload.");
        }

        List<Order> orders = new ArrayList<>();
        for (Object item : rawOrders) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("Each order must be a JSON object.");
            }
            orders.add(mapToOrder((Map<String, Object>) entry));
        }
        return orders;
    }

    private static List<Order> parseTextOrders(String requestBody) {
        List<Order> orders = new ArrayList<>();
        String[] lines = requestBody.split("\\R");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }

            String[] parts = trimmedLine.split(",");
            if (parts.length != 6) {
                throw new IllegalArgumentException("Text format must be: id,weight,volume,cost,priority,zone");
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", parts[0].trim());
            map.put("weight", Integer.parseInt(parts[1].trim()));
            map.put("volume", Integer.parseInt(parts[2].trim()));
            map.put("cost", Integer.parseInt(parts[3].trim()));
            map.put("priority", parts[4].trim());
            map.put("zone", parts[5].trim());
            orders.add(mapToOrder(map));
        }
        return orders;
    }

    private static Order mapToOrder(Map<String, Object> orderMap) {
        String id = requireString(orderMap, "id");
        int weight = requireInt(orderMap, "weight");
        int volume = requireInt(orderMap, "volume");
        int cost = requireInt(orderMap, "cost");
        String priority = requireString(orderMap, "priority");
        String zone = requireString(orderMap, "zone");

        if (weight <= 0 || volume <= 0 || cost < 0) {
            throw new IllegalArgumentException("Weight and volume must be positive, and cost cannot be negative.");
        }
        if (weight > MAX_WEIGHT || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("An order exceeds the max container capacity of 50 weight and 50 volume.");
        }

        return new Order(id, weight, volume, cost, normalizePriority(priority), zone);
    }

    private static String normalizePriority(String priority) {
        String normalized = priority.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high", "3" -> "High";
            case "medium", "2" -> "Medium";
            case "low", "1" -> "Low";
            default -> throw new IllegalArgumentException("Priority must be High, Medium, or Low.");
        };
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Field cannot be empty: " + key);
        }
        return text;
    }

    private static int requireInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value).trim());
    }

    private static String readRequestBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        sendText(exchange, statusCode, body, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String detectContentType(String fileName) {
        if (fileName.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (fileName.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (fileName.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "text/plain; charset=utf-8";
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private record Order(String id, int weight, int volume, int cost, String priority, String zone) {
        int priorityValue() {
            return switch (priority) {
                case "High" -> 3;
                case "Medium" -> 2;
                default -> 1;
            };
        }

        int shippingFootprint() {
            return weight + volume;
        }
    }

    private static class Container {
        private final int index;
        private final List<Order> orders = new ArrayList<>();
        private final Map<String, Integer> zoneCounts = new LinkedHashMap<>();
        private int currentWeight;
        private int currentVolume;

        private Container(int index) {
            this.index = index;
        }

        private boolean canFit(Order order) {
            return currentWeight + order.weight <= MAX_WEIGHT && currentVolume + order.volume <= MAX_VOLUME;
        }

        private void add(Order order) {
            orders.add(order);
            currentWeight += order.weight;
            currentVolume += order.volume;
            zoneCounts.put(order.zone, zoneCounts.getOrDefault(order.zone, 0) + 1);
        }

        private String outputLabel() {
            StringBuilder builder = new StringBuilder();
            builder.append("Container ").append(index).append(":\n");
            for (int i = 0; i < orders.size(); i++) {
                builder.append(orders.get(i).id);
                if (i < orders.size() - 1) {
                    builder.append(", ");
                }
            }
            return builder.toString();
        }
    }

    private static class OptimizationResult {
        private final List<Container> containers;

        private OptimizationResult(List<Container> containers) {
            this.containers = containers;
        }

        private String toJson() {
            StringBuilder builder = new StringBuilder();
            builder.append("{");
            builder.append("\"containerCount\":").append(containers.size()).append(",");
            builder.append("\"containers\":[");

            for (int i = 0; i < containers.size(); i++) {
                Container container = containers.get(i);
                builder.append("{");
                builder.append("\"name\":\"Container ").append(container.index).append("\",");
                builder.append("\"orders\":[");

                for (int j = 0; j < container.orders.size(); j++) {
                    Order order = container.orders.get(j);
                    builder.append("{");
                    builder.append("\"id\":\"").append(escapeJson(order.id)).append("\",");
                    builder.append("\"weight\":").append(order.weight).append(",");
                    builder.append("\"volume\":").append(order.volume).append(",");
                    builder.append("\"cost\":").append(order.cost).append(",");
                    builder.append("\"priority\":\"").append(order.priority).append("\",");
                    builder.append("\"zone\":\"").append(escapeJson(order.zone)).append("\"");
                    builder.append("}");
                    if (j < container.orders.size() - 1) {
                        builder.append(",");
                    }
                }

                builder.append("],");
                builder.append("\"totalWeight\":").append(container.currentWeight).append(",");
                builder.append("\"totalVolume\":").append(container.currentVolume).append(",");
                builder.append("\"weightUtilization\":").append(String.format(Locale.US, "%.2f", (container.currentWeight * 100.0) / MAX_WEIGHT)).append(",");
                builder.append("\"volumeUtilization\":").append(String.format(Locale.US, "%.2f", (container.currentVolume * 100.0) / MAX_VOLUME)).append(",");
                builder.append("\"display\":\"").append(escapeJson(container.outputLabel())).append("\"");
                builder.append("}");

                if (i < containers.size() - 1) {
                    builder.append(",");
                }
            }

            builder.append("]");
            builder.append("}");
            return builder.toString();
        }
    }

    private static class JsonParser {
        private final String text;
        private int index;

        private JsonParser(String text) {
            this.text = text;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException("Unexpected characters in JSON.");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON.");
            }

            char current = text.charAt(index);
            return switch (current) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (current == '-' || Character.isDigit(current)) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Invalid JSON value.");
                }
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();

            if (peek('}')) {
                expect('}');
                return map;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();

                if (peek('}')) {
                    expect('}');
                    break;
                }
                expect(',');
            }

            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();

            if (peek(']')) {
                expect(']');
                return list;
            }

            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    break;
                }
                expect(',');
            }

            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();

            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\') {
                    if (index >= text.length()) {
                        throw new IllegalArgumentException("Invalid escape sequence.");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> builder.append(escaped);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape.");
                            }
                            String hex = text.substring(index, index + 4);
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw new IllegalArgumentException("Unsupported escape character.");
                    }
                } else {
                    builder.append(current);
                }
            }

            throw new IllegalArgumentException("Unterminated string.");
        }

        private Object parseNumber() {
            int start = index;
            if (text.charAt(index) == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
                return Double.parseDouble(text.substring(start, index));
            }
            return Integer.parseInt(text.substring(start, index));
        }

        private Boolean parseBoolean() {
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean value.");
        }

        private Object parseNull() {
            if (!text.startsWith("null", index)) {
                throw new IllegalArgumentException("Invalid null value.");
            }
            index += 4;
            return null;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' in JSON.");
            }
            index++;
        }

        private boolean peek(char expected) {
            skipWhitespace();
            return index < text.length() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}

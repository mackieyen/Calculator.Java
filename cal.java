
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Calculator written in Java. It runs a small web server, builds the HTML page,
 * and does all the math on the server. No JavaScript is used.
 *
 * Run (Java 17+): java Calculator.java Then open: http://localhost:8080 Keep
 * style.css in the same folder you run the command from.
 */
class Calculator {

    static final String ALLOWED = "0123456789.+-*/%";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", Calculator::handlePage);
        server.createContext("/style.css", Calculator::handleCss);
        server.start();
        System.out.println("Calculator running at http://localhost:8080");
    }

    // ---------- HTTP handlers ----------
    static void handlePage(HttpExchange ex) throws IOException {
        String expr = "";
        String history = "";
        boolean done = false;

        if (ex.getRequestMethod().equals("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseForm(body);
            expr = clean(form.getOrDefault("expr", ""));
            done = "1".equals(form.get("done"));
            String key = form.getOrDefault("key", "");

            if (expr.equals("Error")) {
                expr = "";
                done = false;
            }

            switch (key) {
                case "AC" -> {
                    expr = "";
                    done = false;
                }
                case "back" -> {
                    if (!done && !expr.isEmpty()) {
                        expr = expr.substring(0, expr.length() - 1);
                    }
                }
                case "=" -> {
                    if (!expr.isEmpty()) {
                        history = expr + " =";
                        try {
                            expr = format(new Parser(expr).parse());
                        } catch (RuntimeException e) {
                            expr = "Error";
                        }
                        done = true;
                    }
                }
                default -> {
                    if (done && key.matches("[0-9.]")) {
                        expr = ""; // start a new number after "="

                    }
                    done = false;
                    if (key.length() == 1 && ALLOWED.indexOf(key.charAt(0)) >= 0) {
                        expr += key;
                    }
                }
            }
        }

        send(ex, "text/html; charset=utf-8", render(expr, history, done).getBytes(StandardCharsets.UTF_8));
    }

    static void handleCss(HttpExchange ex) throws IOException {
        send(ex, "text/css; charset=utf-8", Files.readAllBytes(Path.of("style.css")));
    }

    static void send(HttpExchange ex, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    // ---------- HTML ----------
    static String render(String expr, String history, boolean done) {
        String shown = expr.isEmpty() ? "0" : show(expr);
        String size = shown.length() > 12 ? "s" : shown.length() > 9 ? "m" : "";

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Java Calculator</title><link rel=\"stylesheet\" href=\"/style.css\"></head>")
                .append("<body><main class=\"calc\"><div class=\"screen\">")
                .append("<div class=\"history\">").append(show(history)).append("</div>")
                .append("<div class=\"display ").append(size).append("\">").append(shown).append("</div></div>")
                .append("<form class=\"keys\" method=\"post\" action=\"/\">")
                .append("<input type=\"hidden\" name=\"expr\" value=\"").append(expr).append("\">")
                .append("<input type=\"hidden\" name=\"done\" value=\"").append(done ? "1" : "0").append("\">");

        sb.append(button("clr", "AC", "AC"))
                .append(button("op", "back", "&#9003;"))
                .append(button("op", "%", "&#37;"))
                .append(button("op", "/", "&divide;"));
        sb.append(button("", "7", "7")).append(button("", "8", "8")).append(button("", "9", "9"))
                .append(button("op", "*", "&times;"));
        sb.append(button("", "4", "4")).append(button("", "5", "5")).append(button("", "6", "6"))
                .append(button("op", "-", "&minus;"));
        sb.append(button("", "1", "1")).append(button("", "2", "2")).append(button("", "3", "3"))
                .append(button("op", "+", "+"));
        sb.append(button("zero", "0", "0")).append(button("", ".", ".")).append(button("eq", "=", "="));

        sb.append("</form></main></body></html>");
        return sb.toString();
    }

    static String button(String cls, String value, String label) {
        return "<button class=\"" + cls + "\" type=\"submit\" name=\"key\" value=\"" + value + "\">"
                + label + "</button>";
    }

    // Show * and / as the proper symbols
    static String show(String s) {
        return s.replace('*', '\u00d7').replace('/', '\u00f7');
    }

    // ---------- Helpers ----------
    static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    // Keep only characters the calculator can produce
    static String clean(String s) {
        if (s.equals("Error")) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (ALLOWED.indexOf(c) >= 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new ArithmeticException("Invalid result");
        }
        return new BigDecimal(v).round(new MathContext(12)).stripTrailingZeros().toPlainString();
    }

    // ---------- Expression parser: + - * / % and unary minus ----------
    static class Parser {

        final String s;
        int p = 0;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = expression();
            if (p < s.length()) {
                throw new IllegalArgumentException("Unexpected input");
            }
            return v;
        }

        double expression() {
            double v = term();
            while (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
                char op = s.charAt(p++);
                double t = term();
                v = op == '+' ? v + t : v - t;
            }
            return v;
        }

        double term() {
            double v = factor();
            while (p < s.length() && (s.charAt(p) == '*' || s.charAt(p) == '/')) {
                char op = s.charAt(p++);
                double f = factor();
                if (op == '/') {
                    if (f == 0) {
                        throw new ArithmeticException("Divide by zero");
                    }
                    v /= f;
                } else {
                    v *= f;
                }
            }
            return v;
        }

        double factor() {
            if (p < s.length() && s.charAt(p) == '-') {
                p++;
                return -factor();
            }
            int start = p;
            while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) {
                p++;
            }
            if (start == p) {
                throw new IllegalArgumentException("Number expected");
            }
            double v = Double.parseDouble(s.substring(start, p));
            while (p < s.length() && s.charAt(p) == '%') {
                p++;
                v /= 100;
            }
            return v;
        }
    }
}

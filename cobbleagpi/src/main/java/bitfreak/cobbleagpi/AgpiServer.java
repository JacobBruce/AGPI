package bitfreak.cobbleagpi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


public final class AgpiServer {
	private static final int MAX_BODY = 64 * 1024;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private static HttpServer server;
	private static ExecutorService pool;

	private AgpiServer() {}

	public static void Start() {
		Stop();
		int port = ModConfig.Port();
		try {
			HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
			http.createContext("/", AgpiServer::Handle);
			ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
				Thread thread = new Thread(r, "cobbleagpi-http");
				thread.setDaemon(true);
				return thread;
			});
			http.setExecutor(workers);
			pool = workers;
			http.start();
			server = http;
			CobbleAGPI.LOGGER.info("AGPI listening on http://127.0.0.1:{}", port);
		} catch (IOException err) {
			CobbleAGPI.LOGGER.error("Could not bind 127.0.0.1:{}. {}", port, err.getMessage());
		}
	}

	public static void Stop() {
		HttpServer http = server;
		server = null;
		if (http == null) return;
		http.stop(0);
		ExecutorService workers = pool;
		pool = null;
		if (workers != null) workers.shutdownNow();
	}

	private static void Handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String method = exchange.getRequestMethod();
		// Only Loci's calls count; the status page polls /v1/log.
		if ("/v1/hello".equals(path) || "/v1/events".equals(path) || "/v1/call".equals(path)) BattleWatch.Touch();
		try {
			if ("GET".equals(method) && "/".equals(path)) {
				Send(exchange, 200, "text/html; charset=utf-8", Page().getBytes(StandardCharsets.UTF_8));
				return;
			}
			if ("GET".equals(method) && "/v1/hello".equals(path)) {
				SendJson(exchange, 200, Map.of(
					"ok", true,
					"game", "cobblemon",
					"name", "Cobblemon",
					"skill", "cobblemon.md"
				));
				return;
			}
			if ("GET".equals(method) && "/v1/events".equals(path)) {
				SendJson(exchange, 200, BattleWatch.Drain());
				return;
			}
			if ("GET".equals(method) && "/v1/log".equals(path)) {
				SendJson(exchange, 200, BattleWatch.Log());
				return;
			}
			if ("POST".equals(method) && ("/v1/call".equals(path) || "/v1/inject".equals(path))) {
				String raw = ReadBody(exchange);
				Map<?, ?> data = Map.of();
				if (!raw.isBlank()) {
					try {
						Object parsed = GSON.fromJson(raw, Map.class);
						if (parsed instanceof Map<?, ?> map) data = map;
						else {
							SendJson(exchange, 400, Map.of("ok", false, "error", "Body was not a JSON object."));
							return;
						}
					} catch (JsonSyntaxException err) {
						SendJson(exchange, 400, Map.of("ok", false, "error", "Body was not JSON."));
						return;
					}
				}
				if ("/v1/inject".equals(path)) {
					SendJson(exchange, 200, BattleWatch.Inject(data));
					return;
				}
				SendJson(exchange, 200, BattleWatch.Call(Field(data, "name"), Args(data.get("args"))));
				return;
			}
			SendJson(exchange, 404, Map.of("ok", false, "error", "Not found."));
		} catch (Exception err) {
			if (exchange.getResponseCode() >= 0) {
				exchange.close();
				return;
			}
			String message = err.getMessage();
			if (message == null || message.isBlank()) message = "The game failed.";
			SendJson(exchange, 500, Map.of("ok", false, "error", message));
		}
	}

	private static String Field(Map<?, ?> data, String key) {
		Object value = data.get(key);
		return value == null ? "" : String.valueOf(value);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> Args(Object raw) {
		if (raw instanceof Map<?, ?> map) return (Map<String, Object>) map;
		return Map.of();
	}

	private static String ReadBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] buf = in.readNBytes(MAX_BODY + 1);
			if (buf.length > MAX_BODY) throw new IllegalStateException("Body is too large.");
			return new String(buf, StandardCharsets.UTF_8);
		}
	}

	private static void SendJson(HttpExchange exchange, int status, Object body) throws IOException {
		Send(exchange, status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
	}

	private static void Send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("content-type", type);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static String Page() {
		return """
			<!DOCTYPE html>
			<html lang="en">
			<head><meta charset="utf-8"><title>Cobblemon</title></head>
			<body>
			<h1>Cobblemon</h1>
			<p>Loci connects here. Trainer lines show up in Minecraft chat.</p>
			<p>Install <code>cobblemon.md</code> as a skill before connecting.</p>
			<pre id="log">(quiet)</pre>
			<script>
			async function Paint() {
				const res = await fetch('/v1/log');
				const data = await res.json();
				const lines = (data.lines || []).map((row) => row.as + ': ' + row.text);
				document.getElementById('log').textContent = lines.length ? lines.join('\\n') : '(quiet)';
			}
			Paint();
			setInterval(Paint, 1000);
			</script>
			</body>
			</html>
			""";
	}
}

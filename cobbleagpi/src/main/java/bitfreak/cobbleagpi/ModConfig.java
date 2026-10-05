package bitfreak.cobbleagpi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

public final class ModConfig {
	public static final int DEFAULT_PORT = 24740;
	public static final int DEFAULT_MOVE_SECONDS = 30;

	private static int port = DEFAULT_PORT;
	private static int moveSeconds = DEFAULT_MOVE_SECONDS;

	private ModConfig() {}

	public static void Read() {
		Properties props = Load();
		port = Clamp(Number(props, "port", DEFAULT_PORT), 1, 65535);
		moveSeconds = Clamp(Number(props, "move_seconds", DEFAULT_MOVE_SECONDS), 5, 300);
	}

	public static int Port() {
		return port;
	}

	public static int MoveSeconds() {
		return moveSeconds;
	}

	private static Properties Load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("cobbleagpi.properties");
		Properties props = new Properties();
		try {
			if (!Files.exists(path)) {
				Files.createDirectories(path.getParent());
				Files.writeString(path, "port=" + DEFAULT_PORT + "\nmove_seconds=" + DEFAULT_MOVE_SECONDS + "\n");
			}
			try (var reader = Files.newBufferedReader(path)) {
				props.load(reader);
			}
		} catch (IOException err) {
			CobbleAGPI.LOGGER.warn("Could not read {}.", path);
		}
		return props;
	}

	private static int Number(Properties props, String key, int fallback) {
		try {
			return Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
		} catch (NumberFormatException err) {
			return fallback;
		}
	}

	private static int Clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}

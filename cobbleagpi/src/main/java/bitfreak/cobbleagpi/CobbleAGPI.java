package bitfreak.cobbleagpi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CobbleAGPI implements ModInitializer {
	public static final String MOD_ID = "cobbleagpi";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ModConfig.Read();
			BattleWatch.Start(server);
			AgpiServer.Start();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			AgpiServer.Stop();
			BattleWatch.Stop();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> BattleWatch.Tick());
	}
}

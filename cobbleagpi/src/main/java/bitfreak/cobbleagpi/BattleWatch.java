package bitfreak.cobbleagpi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctapi.api.RCTApi;
import com.gitlab.srcmc.rctapi.api.battle.BattleState;
import com.gitlab.srcmc.rctapi.api.events.EventListener;
import com.gitlab.srcmc.rctapi.api.events.Events;
import com.gitlab.srcmc.rctapi.api.trainer.Trainer;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerPlayer;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerRegistry;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class BattleWatch {
	private static final int EVENT_CAP = 32;
	private static final int LINE_CAP = 40;
	private static final int TEXT_MAX = 2000;
	private static final Object LOCK = new Object();

	private static MinecraftServer server;
	private static final List<Hook> hooks = new ArrayList<>();
	private static final List<Map<String, Object>> events = new ArrayList<>();
	private static final List<Map<String, Object>> lines = new ArrayList<>();
	private static final long CONTACT_MS = 10_000;
	private static final int HOOK_EVERY = 20;
	private static int hookTicks;
	private static int shownLog;
	private static boolean asked;
	private static volatile long lastContact;
	private static Fight fight;

	private BattleWatch() {}

	public static void Start(MinecraftServer next) {
		synchronized (LOCK) {
			server = next;
			fight = null;
			events.clear();
			ForgetChat();
		}
		EnsureHooks();
	}

	/** Any request from Loci. A battle is only taken over while Loci is listening. */
	public static void Touch() {
		lastContact = System.currentTimeMillis();
	}

	public static boolean Connected() {
		return System.currentTimeMillis() - lastContact < CONTACT_MS;
	}

	public static void Stop() {
		synchronized (LOCK) {
			for (Hook hook : hooks) hook.close();
			hooks.clear();
			fight = null;
			server = null;
			ForgetChat();
			TurnControl.Reset();
		}
	}

	public static void Tick() {
		// RCT instances register once at mod init, so a second is soon enough to notice a new one.
		if (hooks.isEmpty() || ++hookTicks >= HOOK_EVERY) {
			hookTicks = 0;
			EnsureHooks();
		}
		TurnControl.Tick();
	}

	/** Runs on the server thread so the battle log can be read at the moment Loci collects the message. */
	public static Map<String, Object> Drain() {
		return OnServer(BattleWatch::DrainHere, 3);
	}

	private static Map<String, Object> DrainHere() {
		Fight current = fight;
		BattleState state = Fresh(current);
		PokemonBattle battle = state == null ? null : state.getBattle();
		synchronized (LOCK) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("ok", true);
			Map<String, Object> merged = Merge(events);
			events.clear();
			List<Map<String, Object>> out = new ArrayList<>();
			if (merged != null) {
				Object choice = merged.remove("choice");
				if (choice instanceof Number number) {
					asked = true;
					TurnControl.Announce(number.intValue());
				}
				if (!"end".equals(merged.get("type")) && battle != null) {
					merged.put("text", String.valueOf(merged.get("text")) + TakeFresh(battle));
				}
				out.add(merged);
			}
			body.put("events", out);
			return body;
		}
	}

	public static Map<String, Object> Log() {
		synchronized (LOCK) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("ok", true);
			body.put("lines", new ArrayList<>(lines));
			return body;
		}
	}

	public static Map<String, Object> Inject(Map<?, ?> data) {
		String type = "end".equals(String.valueOf(data.get("type"))) ? "end" : "wake";
		synchronized (LOCK) {
			Push(type, Clip(Field(data, "reason"), 80), Clip(Field(data, "text"), 500));
		}
		return Map.of("ok", true);
	}

	public static Map<String, Object> Call(String name, Map<String, Object> args) {
		return OnServer(() -> CallHere(name, args == null ? Map.of() : args), 8);
	}

	private static void EnsureHooks() {
		MinecraftServer current = server;
		if (current == null || !current.isSameThread()) return;
		synchronized (LOCK) {
			Set<String> seen = new HashSet<>();
			for (Hook hook : hooks) seen.add(hook.apiId);
			RCTApi.getInstances().forEach(entry -> {
				String apiId = entry.getKey();
				if (apiId == null || seen.contains(apiId)) return;
				RCTApi api = entry.getValue();
				if (api == null || api.getEventContext() == null) return;
				Hook hook = new Hook(apiId, api);
				api.getEventContext().register(Events.BATTLE_STARTED, hook.started);
				api.getEventContext().register(Events.BATTLE_ENDED, hook.ended);
				hooks.add(hook);
				seen.add(apiId);
			});
		}
	}

	private static void OnStarted(String apiId, BattleState state) {
		if (state == null || state.getBattle() == null) return;
		List<TrainerNPC> opponents = Opponents(state);
		if (opponents.isEmpty()) return;
		RCTApi api = RCTApi.getInstance(apiId);
		TrainerNPC npc = opponents.get(0);
		String id = TrainerId(api, npc);
		String name = NameOf(npc);
		synchronized (LOCK) {
			// The player is already in a battle, so another one is not started.
			if (fight != null) {
				CobbleAGPI.LOGGER.info("Ignored a second RCT battle while {} is still running.", fight.lockedId);
				return;
			}
			fight = new Fight(apiId, state.getBattle().getBattleId(), id, name);
			TurnControl.Reset();
			ForgetChat();
			if (Connected()) Push("wake", "battle_start", "A battle with " + name + " has started. You are locked as " + name + ". You choose this trainer's moves. Comment on the battle with send_msg.");
		}
	}

	private static void OnEnded(BattleState state) {
		if (state == null || state.getBattle() == null) return;
		synchronized (LOCK) {
			if (fight == null || !state.getBattle().getBattleId().equals(fight.battleId)) return;
			String name = fight.lockedName;
			boolean won = Won(state, fight);
			boolean lost = Lost(state, fight);
			fight = null;
			TurnControl.Reset();
			String text = "The battle with " + name + " has ended.";
			if (won) text += " " + name + " won.";
			else if (lost) text += " " + name + " lost.";
			if (!asked) text += " You were not asked to choose.";
			text += TakeFresh(state.getBattle());
			ForgetChat();
			if (Connected()) Push("wake", "battle_end", text);
		}
	}

	private static Map<String, Object> CallHere(String name, Map<String, Object> args) {
		if ("list_actions".equals(name)) return ListActions(Str(args.get("target")));
		if ("list_objects".equals(name)) return ListObjects(Str(args.get("type")).toLowerCase(), args.get("limit"));
		if ("get_state".equals(name)) return GetState(Str(args.get("query")).toLowerCase());
		if ("lock_body".equals(name)) return LockBody(Str(args.get("target")));
		if ("send_msg".equals(name)) return SendMsg(Str(args.get("text")));
		if ("send_action".equals(name)) return SendAction(Str(args.get("action")).toLowerCase(), Str(args.get("target")), Str(args.get("payload")));
		if ("get_screen".equals(name)) return Fail("This game cannot capture the screen.");
		return Fail("Unknown tool: " + name);
	}

	private static Map<String, Object> ListActions(String target) {
		if (TurnControl.Waiting() && !TurnControl.Announced()) {
			return Ok(Map.of("actions", List.of(), "text", TurnControl.WaitNote()));
		}
		if (TurnControl.Waiting()) {
			List<Map<String, Object>> actions = TurnControl.Actions(target);
			if (actions == null) return Fail("Unknown target: " + target + ".");
			if (!target.isBlank()) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("ok", true);
				body.put("target", target);
				body.put("actions", actions);
				return body;
			}
			return Ok(Map.of("actions", actions));
		}
		if (!target.isBlank()) {
			TrainerNPC npc = FindOpponent(target);
			if (npc == null) return Fail("Unknown target: " + target + ".");
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("ok", true);
			body.put("target", TrainerId(Api(fight), npc));
			body.put("actions", List.of());
			if (fight != null) body.put("text", TurnControl.IdleNote());
			return body;
		}
		if (fight == null) return Ok(Map.of("actions", List.of()));
		return Ok(Map.of("actions", List.of(), "text", TurnControl.IdleNote()));
	}

	private static Map<String, Object> ListObjects(String type, Object limitRaw) {
		if (!type.isBlank() && !type.equals("item") && !type.equals("body") && !type.equals("exit")) {
			return Fail("Unknown type: " + type + ". Valid: item, body, exit.");
		}
		List<Map<String, Object>> items = new ArrayList<>();
		if (type.isBlank() || type.equals("body")) {
			for (Map<String, Object> row : Bodies()) items.add(row);
		}
		if (type.equals("item")) {
			items.addAll(TurnControl.Items());
		}
		int cap = 50;
		if (limitRaw instanceof Number number && number.intValue() > 0) cap = Math.min(50, number.intValue());
		if (items.size() > cap) items = new ArrayList<>(items.subList(0, cap));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("type", type.isBlank() ? "body" : type);
		body.put("room", Room());
		body.put("items", items);
		if (type.isBlank()) {
			body.put("types", List.of(
				Map.of("id", "item", "when", "Items in the locked trainer's bag."),
				Map.of("id", "body", "when", "RCT trainers you can lock. The opponent is locked when the battle starts."),
				Map.of("id", "exit", "when", "No way out of a battle except the battle ending.")
			));
		}
		return body;
	}

	private static Map<String, Object> GetState(String query) {
		if (query.equals("battle_log")) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("query", "battle_log");
			body.put("lines", ChatLog());
			return Ok(body);
		}
		if (!query.isBlank() && !query.equals("here") && !query.equals("default") && !query.equals("room") && !query.equals("locked")) {
			BattlePokemon mon = FindMon(query);
			if (mon == null) return Fail("Unknown query: " + query + ". Valid: (empty), room, locked, battle_log, or a Pokemon id.");
			return Ok(PokemonCard(mon));
		}
		Map<String, Object> snap = Snapshot();
		if (query.equals("room") || query.equals("locked")) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("query", query);
			body.put(query.equals("room") ? "room" : "locked", snap.get(query.equals("room") ? "room" : "locked"));
			return Ok(body);
		}
		return snap;
	}

	private static Map<String, Object> LockBody(String target) {
		Fight current = fight;
		List<Map<String, Object>> available = Bodies();
		if (current == null) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("ok", true);
			body.put("connected", true);
			body.put("selected", null);
			body.put("available", available);
			return body;
		}
		if (!target.isBlank()) {
			TrainerNPC npc = FindOpponent(target);
			if (npc == null) {
				return Fail("Unknown body: \"" + target + "\". Valid: " + Ids(available) + ". The previous lock is unchanged.");
			}
			current.lockedId = TrainerId(Api(current), npc);
			current.lockedName = NameOf(npc);
		}
		Map<String, Object> selected = new LinkedHashMap<>();
		selected.put("id", current.lockedId);
		selected.put("name", current.lockedName);
		selected.put("room", "battle");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("connected", true);
		body.put("selected", selected);
		body.put("available", available);
		return body;
	}

	private static Map<String, Object> SendMsg(String text) {
		String line = text.trim();
		if (line.isBlank()) return Fail("text is required");
		if (line.length() > TEXT_MAX) return Fail("text is at most " + TEXT_MAX + " characters.");
		Fight current = fight;
		if (current == null || server == null) return Fail("No battle is active.");
		BattleState state = Fresh(current);
		if (state == null) return Fail("No battle is active.");
		Component chat = Component.literal("<" + current.lockedName + "> " + line);
		int sent = 0;
		for (ServerPlayer player : Players(state)) {
			player.sendSystemMessage(chat);
			sent++;
		}
		if (sent == 0) return Fail("No player is in this battle.");
		synchronized (LOCK) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("as", current.lockedId);
			row.put("text", line);
			lines.add(row);
			while (lines.size() > LINE_CAP) lines.remove(0);
		}
		return Ok(Map.of("delivered", true, "as", current.lockedId, "text", line));
	}

	private static Map<String, Object> SendAction(String action, String target, String payload) {
		if (action.isBlank()) return Fail("action is required. Call list_actions to see valid verbs.");
		if (!TurnControl.Waiting()) return Fail(TurnControl.NotYourTurn(payload));
		if (!TurnControl.Announced()) return Fail(TurnControl.WaitNote());
		return TurnControl.Act(action, target, payload);
	}

	private static Map<String, Object> Snapshot() {
		Fight current = fight;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("game", "cobblemon");
		if (current == null) {
			Map<String, Object> room = Room();
			room.put("text", "No battle is active.");
			body.put("room", room);
			body.put("locked", null);
			body.put("turn", 0);
			body.put("text", "No battle is active.");
			return body;
		}
		BattleState state = Fresh(current);
		PokemonBattle battle = state == null ? null : state.getBattle();
		if (battle == null) {
			body.put("room", Room());
			body.put("locked", null);
			body.put("turn", 0);
			body.put("text", "No battle is active.");
			return body;
		}
		TrainerNPC npc = LockedNpc(current, state);
		BattleActor ours = npc == null ? null : actorFor(battle, npc);
		int choice = TurnControl.OpenChoice();
		String text = FieldText(battle, ours, current.lockedName);
		if (choice <= 0) text += "\nThe turn is still playing out, so HP may change.";
		Map<String, Object> room = Room();
		room.put("text", text);
		Map<String, Object> locked = new LinkedHashMap<>();
		locked.put("id", current.lockedId);
		locked.put("name", current.lockedName);
		body.put("room", room);
		body.put("locked", locked);
		body.put("turn", battle.getTurn());
		body.put("choice", choice > 0 ? choice : null);
		body.put("phase", choice > 0 ? "choosing" : "playing");
		body.put("text", text);
		return body;
	}

	private static String FieldText(PokemonBattle battle, BattleActor ours, String name) {
		StringBuilder out = new StringBuilder();
		String kind = "battle";
		if (battle.getFormat() != null && battle.getFormat().getBattleType() != null) {
			var type = battle.getFormat().getBattleType();
			kind = type.getDisplayName() == null ? type.getName() : type.getDisplayName().getString();
		}
		out.append("Turn ").append(battle.getTurn()).append(". ").append(kind).append(".\n");
		out.append(PartyText(ours, name));
		out.append("\nOpponent active:\n");
		boolean any = false;
		for (BattleActor actor : battle.getActors()) {
			if (actor == ours) continue;
			for (ActiveBattlePokemon slot : actor.getActivePokemon()) {
				BattlePokemon bp = slot.getBattlePokemon();
				if (bp == null || bp.getOriginalPokemon() == null) continue;
				any = true;
				out.append("- ").append(MonLine(bp, false)).append('\n');
			}
		}
		if (!any) out.append("- none\n");
		return out.toString().trim();
	}

	private static String PartyText(BattleActor actor, String name) {
		StringBuilder out = new StringBuilder();
		out.append(name).append(":\n");
		if (actor == null) {
			out.append("- party unavailable\n");
			return out.toString().trim();
		}
		Set<UUID> active = new HashSet<>();
		for (ActiveBattlePokemon slot : actor.getActivePokemon()) {
			BattlePokemon bp = slot.getBattlePokemon();
			if (bp != null && bp.getOriginalPokemon() != null) active.add(bp.getOriginalPokemon().getUuid());
		}
		for (BattlePokemon bp : actor.getPokemonList()) {
			if (bp.getOriginalPokemon() == null) continue;
			boolean onField = active.contains(bp.getOriginalPokemon().getUuid());
			out.append("- ").append(bp.getUuid()).append(' ').append(MonLine(bp, true));
			if (onField) out.append(" [active]");
			out.append('\n');
		}
		return out.toString().trim();
	}

	private static String MonLine(BattlePokemon bp, boolean withMoves) {
		Pokemon mon = bp.getEffectedPokemon() != null ? bp.getEffectedPokemon() : bp.getOriginalPokemon();
		String name = mon.getDisplayName(false).getString();
		int max = Math.max(1, mon.getMaxHealth());
		StringBuilder line = new StringBuilder();
		line.append(name).append(" lv").append(mon.getLevel()).append(' ').append(bp.getHealth()).append('/').append(max).append(" HP");
		if (bp.getHealth() <= 0) line.append(" fainted");
		String ability = AbilityName(mon);
		if (!ability.isBlank()) line.append(". Ability ").append(ability);
		if (withMoves) {
			List<String> moves = new ArrayList<>();
			if (bp.getMoveSet() != null) {
				for (var move : bp.getMoveSet().getMoves()) {
					if (move != null && move.getDisplayName() != null) moves.add(move.getDisplayName().getString());
				}
			}
			if (!moves.isEmpty()) line.append(". Moves ").append(String.join(", ", moves));
			ItemStack held = mon.getHeldItem$common();
			if (held != null && !held.isEmpty()) line.append(". Holds ").append(held.getHoverName().getString());
		}
		return line.toString();
	}

	public static boolean Owns(BattleActor actor) {
		if (actor == null || fight == null) return false;
		BattleState state = Fresh(fight);
		if (state == null || state.getBattle() == null) return false;
		TrainerNPC npc = LockedNpc(fight, state);
		return npc != null && actorFor(state.getBattle(), npc) == actor;
	}

	public static String LockedName() {
		return fight == null ? "Trainer" : fight.lockedName;
	}

	public static TrainerNPC LockedNpc() {
		if (fight == null) return null;
		BattleState state = Fresh(fight);
		return state == null ? null : LockedNpc(fight, state);
	}

	public static void Wake(String reason, String text, int choice) {
		synchronized (LOCK) {
			if (!events.isEmpty()) {
				Map<String, Object> last = events.get(events.size() - 1);
				if ("battle_start".equals(last.get("reason"))) {
					Object base = last.get("base");
					if (base == null) {
						base = last.get("text");
						last.put("base", base);
					}
					last.put("text", base + " " + text);
					last.put("choice", choice);
					return;
				}
			}
			Push("wake", reason, text);
			events.get(events.size() - 1).put("choice", choice);
		}
	}

	public static void DropChoice(int choice) {
		synchronized (LOCK) {
			events.removeIf(event -> "your_turn".equals(event.get("reason")) && Integer.valueOf(choice).equals(event.get("choice")));
		}
	}

	/**
	 * Loci keeps one event per read, so everything queued becomes one message.
	 * An AGPI end wins outright. The last choice carried is the one announced.
	 */
	private static Map<String, Object> Merge(List<Map<String, Object>> queued) {
		if (queued.isEmpty()) return null;
		for (Map<String, Object> event : queued) {
			if ("end".equals(event.get("type"))) {
				Map<String, Object> end = new LinkedHashMap<>(event);
				end.remove("base");
				end.remove("choice");
				return end;
			}
		}
		StringBuilder text = new StringBuilder();
		Object reason = "";
		Object choice = null;
		for (Map<String, Object> event : queued) {
			if (text.length() > 0) text.append("\n\n");
			text.append(event.get("text"));
			reason = event.get("reason");
			if (event.get("choice") != null) choice = event.get("choice");
		}
		Map<String, Object> merged = new LinkedHashMap<>();
		merged.put("type", "wake");
		merged.put("reason", reason);
		merged.put("text", text.toString());
		if (choice != null) merged.put("choice", choice);
		return merged;
	}

	private static void ForgetChat() {
		shownLog = 0;
		asked = false;
	}

	/** Battle-log lines since the last message, at most the latest 12. Server thread only. */
	private static String TakeFresh(PokemonBattle battle) {
		List<Component> log = battle == null ? null : battle.getChatLog();
		if (log == null) return "";
		int to = log.size();
		if (shownLog > to) shownLog = 0;
		int from = shownLog;
		shownLog = to;
		List<String> fresh = new ArrayList<>();
		for (int i = from; i < to; i++) {
			Component line = log.get(i);
			String text = line == null ? null : line.getString();
			if (text == null || text.isBlank()) continue;
			text = text.replace('\n', ' ').trim();
			fresh.add(text.length() > 140 ? text.substring(0, 140) : text);
		}
		if (fresh.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("\n\nWhat happened:");
		int start = Math.max(0, fresh.size() - 12);
		if (start > 0) sb.append("\n...");
		for (int i = start; i < fresh.size(); i++) sb.append('\n').append(fresh.get(i));
		return sb.toString();
	}

	private static List<String> ChatLog() {
		if (fight == null) return List.of();
		BattleState state = Fresh(fight);
		if (state == null || state.getBattle() == null) return List.of();
		List<String> lines = new ArrayList<>();
		for (var line : state.getBattle().getChatLog()) {
			if (line == null) continue;
			String text = line.getString();
			if (text != null && !text.isBlank()) lines.add(text);
		}
		return lines;
	}

	public static List<BattlePokemon> Party() {
		BattleActor actor = OurActor();
		if (actor == null) return List.of();
		return actor.getPokemonList();
	}

	public static BattlePokemon FindMon(String query) {
		if (query == null || query.isBlank()) return null;
		for (BattlePokemon mon : Party()) {
			if (mon.getUuid() != null && query.equalsIgnoreCase(mon.getUuid().toString())) return mon;
			if (query.equalsIgnoreCase(MonLabel(mon))) return mon;
			Pokemon raw = mon.getOriginalPokemon();
			if (raw != null && raw.getSpecies() != null && query.equalsIgnoreCase(raw.getSpecies().getName())) return mon;
		}
		return null;
	}

	public static String MonLabel(BattlePokemon mon) {
		if (mon == null) return "Pokemon";
		Pokemon raw = mon.getEffectedPokemon() != null ? mon.getEffectedPokemon() : mon.getOriginalPokemon();
		if (raw == null || raw.getDisplayName(false) == null) return "Pokemon";
		return raw.getDisplayName(false).getString();
	}

	public static BattleActor OurActor() {
		if (fight == null) return null;
		BattleState state = Fresh(fight);
		if (state == null || state.getBattle() == null) return null;
		TrainerNPC npc = LockedNpc(fight, state);
		return npc == null ? null : actorFor(state.getBattle(), npc);
	}

	private static Map<String, Object> PokemonCard(BattlePokemon bp) {
		Pokemon mon = bp.getEffectedPokemon() != null ? bp.getEffectedPokemon() : bp.getOriginalPokemon();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", bp.getUuid().toString());
		body.put("name", MonLabel(bp));
		body.put("level", mon.getLevel());
		body.put("hp", bp.getHealth());
		body.put("max_hp", Math.max(1, mon.getMaxHealth()));
		body.put("gender", mon.getGender() == null ? "" : mon.getGender().getShowdownName());
		body.put("nature", mon.getNature() == null ? "" : Translate(mon.getNature().getDisplayName()));
		body.put("ability", AbilityName(mon));
		body.put("attack", mon.getAttack());
		body.put("defense", mon.getDefence());
		body.put("special_attack", mon.getSpecialAttack());
		body.put("special_defense", mon.getSpecialDefence());
		body.put("speed", mon.getSpeed());
		List<String> types = new ArrayList<>();
		if (mon.getTypes() != null) {
			for (var type : mon.getTypes()) {
				if (type != null && type.getDisplayName() != null) types.add(type.getDisplayName().getString());
			}
		}
		body.put("types", types);
		ItemStack held = mon.getHeldItem$common();
		body.put("held", held == null || held.isEmpty() ? "" : held.getHoverName().getString());
		return body;
	}

	private static String AbilityName(Pokemon mon) {
		if (mon.getAbility() == null) return "";
		return Translate(mon.getAbility().getDisplayName());
	}

	private static String Translate(String key) {
		if (key == null || key.isBlank()) return "";
		String translated = Language.getInstance().getOrDefault(key);
		if (translated != null && !translated.isBlank() && !translated.equals(key)) return translated;
		int dot = key.lastIndexOf('.');
		String tail = dot >= 0 ? key.substring(dot + 1) : key;
		StringBuilder words = new StringBuilder();
		for (String part : tail.split("_")) {
			if (part.isBlank()) continue;
			if (!words.isEmpty()) words.append(' ');
			words.append(Character.toUpperCase(part.charAt(0)));
			if (part.length() > 1) words.append(part.substring(1));
		}
		return words.isEmpty() ? key : words.toString();
	}

	private static BattleActor actorFor(PokemonBattle battle, TrainerNPC npc) {
		Set<UUID> ids = new HashSet<>();
		if (npc.getTeam() != null) {
			for (Pokemon mon : npc.getTeam()) {
				if (mon != null) ids.add(mon.getUuid());
			}
		}
		for (BattleActor actor : battle.getActors()) {
			for (BattlePokemon bp : actor.getPokemonList()) {
				Pokemon mon = bp.getOriginalPokemon();
				if (mon != null && ids.contains(mon.getUuid())) return actor;
			}
		}
		return null;
	}

	private static List<TrainerNPC> Opponents(BattleState state) {
		List<TrainerNPC> found = new ArrayList<>();
		boolean left = SideHasPlayer(state.getParticipants1());
		boolean right = SideHasPlayer(state.getParticipants2());
		if (left) AddNpcs(found, state.getParticipants2());
		if (right) AddNpcs(found, state.getParticipants1());
		return found;
	}

	private static boolean SideHasPlayer(List<Trainer> side) {
		if (side == null) return false;
		for (Trainer trainer : side) {
			if (trainer instanceof TrainerPlayer player && player.getPlayer() != null) return true;
		}
		return false;
	}

	private static void AddNpcs(List<TrainerNPC> into, List<Trainer> side) {
		if (side == null) return;
		for (Trainer trainer : side) {
			if (trainer instanceof TrainerNPC npc) into.add(npc);
		}
	}

	private static List<ServerPlayer> Players(BattleState state) {
		List<ServerPlayer> found = new ArrayList<>();
		AddPlayers(found, state.getParticipants1());
		AddPlayers(found, state.getParticipants2());
		return found;
	}

	private static void AddPlayers(List<ServerPlayer> into, List<Trainer> side) {
		if (side == null) return;
		for (Trainer trainer : side) {
			if (trainer instanceof TrainerPlayer player && player.getPlayer() != null) into.add(player.getPlayer());
		}
	}

	private static TrainerNPC LockedNpc(Fight current, BattleState state) {
		RCTApi api = Api(current);
		for (TrainerNPC npc : Opponents(state)) {
			if (current.lockedId.equals(TrainerId(api, npc))) return npc;
		}
		return null;
	}

	private static TrainerNPC FindOpponent(String target) {
		if (fight == null || target.isBlank()) return null;
		BattleState state = Fresh(fight);
		if (state == null) return null;
		RCTApi api = Api(fight);
		for (TrainerNPC npc : Opponents(state)) {
			String id = TrainerId(api, npc);
			if (target.equals(id) || target.equalsIgnoreCase(NameOf(npc))) return npc;
		}
		return null;
	}

	private static List<Map<String, Object>> Bodies() {
		List<Map<String, Object>> rows = new ArrayList<>();
		if (fight == null) return rows;
		BattleState state = Fresh(fight);
		if (state == null) return rows;
		RCTApi api = Api(fight);
		for (TrainerNPC npc : Opponents(state)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", TrainerId(api, npc));
			row.put("name", NameOf(npc));
			rows.add(row);
		}
		return rows;
	}

	private static boolean Won(BattleState state, Fight current) {
		TrainerNPC npc = LockedNpc(current, state);
		if (npc == null || state.getWinners() == null) return false;
		return state.getWinners().contains(npc);
	}

	private static boolean Lost(BattleState state, Fight current) {
		TrainerNPC npc = LockedNpc(current, state);
		if (npc == null || state.getLosers() == null) return false;
		return state.getLosers().contains(npc);
	}

	private static BattleState Fresh(Fight current) {
		if (current == null) return null;
		RCTApi api = RCTApi.getInstance(current.apiId);
		if (api == null || api.getBattleManager() == null) return null;
		return api.getBattleManager().getState(current.battleId);
	}

	private static RCTApi Api(Fight current) {
		if (current == null) return RCTApi.getInstance("");
		return RCTApi.getInstance(current.apiId);
	}

	private static String TrainerId(RCTApi api, Trainer trainer) {
		TrainerRegistry registry = api == null ? null : api.getTrainerRegistry();
		if (registry != null) {
			if (trainer.getEntity() != null) {
				String id = registry.getId(trainer.getEntity());
				if (id != null && !id.isBlank() && registry.getById(id) == trainer) return id;
			}
			for (String id : registry.getIds()) {
				if (registry.getById(id) == trainer) return id;
			}
		}
		return Slug(NameOf(trainer));
	}

	private static String NameOf(Trainer trainer) {
		if (trainer == null || trainer.getName() == null) return "Trainer";
		String name = trainer.getName().getComponent().getString();
		if (name == null || name.isBlank()) name = trainer.getName().getLiteral();
		if (name == null || name.isBlank()) return "Trainer";
		return name;
	}

	private static String Slug(String name) {
		String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		return slug.isBlank() ? "trainer" : slug;
	}

	private static Map<String, Object> Room() {
		Map<String, Object> room = new LinkedHashMap<>();
		room.put("id", "battle");
		room.put("name", fight == null ? "Cobblemon" : fight.lockedName);
		return room;
	}

	private static String Ids(List<Map<String, Object>> bodies) {
		if (bodies.isEmpty()) return "(none)";
		List<String> ids = new ArrayList<>();
		for (Map<String, Object> row : bodies) ids.add(String.valueOf(row.get("id")));
		return String.join(", ", ids);
	}

	private static String Field(Map<?, ?> data, String key) {
		if (data == null) return "";
		return Str(data.get(key));
	}

	private static String Str(Object value) {
		if (value == null) return "";
		String text = String.valueOf(value);
		return "null".equals(text) ? "" : text.trim();
	}

	private static Map<String, Object> Ok(Map<String, Object> fields) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.putAll(fields);
		return body;
	}

	private static Map<String, Object> Fail(String error) {
		return Map.of("ok", false, "error", error);
	}

	private static void Push(String type, String reason, String text) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", type);
		event.put("reason", reason == null ? "" : reason);
		event.put("text", text == null ? "" : text);
		events.add(event);
		while (events.size() > EVENT_CAP) events.remove(0);
	}

	private static String Clip(String text, int max) {
		if (text == null || "null".equals(text)) return "";
		String trimmed = text.trim();
		return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
	}

	private static <T> T OnServer(java.util.function.Supplier<T> work, int seconds) {
		MinecraftServer current;
		synchronized (LOCK) {
			current = server;
		}
		if (current == null) throw new IllegalStateException("Minecraft is not running.");
		if (current.isSameThread()) return work.get();
		CompletableFuture<T> done = new CompletableFuture<>();
		current.execute(() -> {
			// A caller that already gave up must not have its choice sent or its events drained.
			if (done.isDone()) return;
			try {
				done.complete(work.get());
			} catch (Throwable err) {
				done.completeExceptionally(err);
			}
		});
		try {
			return done.get(seconds, TimeUnit.SECONDS);
		} catch (TimeoutException err) {
			done.cancel(false);
			throw new IllegalStateException("The game did not answer in time.");
		} catch (InterruptedException err) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted.");
		} catch (ExecutionException err) {
			Throwable cause = err.getCause() == null ? err : err.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			throw new IllegalStateException(cause.getMessage() == null ? "The game failed." : cause.getMessage());
		}
	}

	private static final class Fight {
		final String apiId;
		final UUID battleId;
		String lockedId;
		String lockedName;

		Fight(String apiId, UUID battleId, String lockedId, String lockedName) {
			this.apiId = apiId;
			this.battleId = battleId;
			this.lockedId = lockedId;
			this.lockedName = lockedName;
		}
	}

	private static final class Hook {
		final String apiId;
		final RCTApi api;
		final EventListener<BattleState> started;
		final EventListener<BattleState> ended = event -> OnEnded(event.getValue());

		Hook(String apiId, RCTApi api) {
			this.apiId = apiId;
			this.api = api;
			this.started = event -> OnStarted(apiId, event.getValue());
		}

		void close() {
			if (api.getEventContext() == null) return;
			api.getEventContext().unregister(Events.BATTLE_STARTED, started);
			api.getEventContext().unregister(Events.BATTLE_ENDED, ended);
		}
	}
}

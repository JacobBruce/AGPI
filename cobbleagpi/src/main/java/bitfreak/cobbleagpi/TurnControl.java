package bitfreak.cobbleagpi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BagItemActionResponse;
import com.cobblemon.mod.common.battles.ForcePassActionResponse;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.PassActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.SwitchActionResponse;
import com.cobblemon.mod.common.battles.Targetable;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.item.battle.BagItem;
import com.gitlab.srcmc.rctapi.api.battle.BattleManager.TrainerEntityBattleActor;
import com.gitlab.srcmc.rctapi.api.battle.BattleState;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerBag;

public final class TurnControl {
	private static Pending pending;
	private static int ticks;
	private static int nextChoice = 1;
	private static int lastSent;
	private static volatile int announced;

	private TurnControl() {}

	public static boolean Waiting() {
		return pending != null;
	}

	public static int OpenChoice() {
		Pending current = pending;
		return current == null ? 0 : current.id;
	}

	public static boolean Announced() {
		Pending current = pending;
		return current != null && current.id == announced;
	}

	public static void Announce(int choice) {
		announced = choice;
	}

	public static String WaitNote() {
		Pending current = pending;
		if (current == null) return IdleNote();
		return "Choice " + current.id + " is open, but its turn message has not been delivered yet. Wait for that message, then call list_actions.";
	}

	public static String IdleNote() {
		if (lastSent > 0) return "No choice is open. Choice " + lastSent + " is in and the turn is playing out. Wait for the next turn message.";
		return "No choice is open. Wait for a turn message.";
	}

	public static String NotYourTurn(String payload) {
		if (lastSent > 0 && payload != null && payload.trim().split("\\s+")[0].equals(String.valueOf(lastSent))) {
			return "Choice " + lastSent + " is already in. Wait for the next turn message.";
		}
		return "It is not your turn. " + IdleNote();
	}

	public static void Clear() {
		pending = null;
		ticks = 0;
	}

	public static void Reset() {
		Clear();
		lastSent = 0;
		announced = 0;
	}

	public static void Tick() {
		Pending current = pending;
		if (current == null) return;
		if (Ended(current.actor)) {
			Clear();
			return;
		}
		// The agent gets the full window from the moment it is told, not from when the choice opened.
		if (!current.shown && current.id == announced) {
			current.shown = true;
			ticks = 0;
		}
		ticks++;
		if (ticks < current.limitTicks) return;
		CobbleAGPI.LOGGER.info("Move timer elapsed. Choosing a legal fallback.");
		for (Slot slot : current.slots) {
			if (slot.response != null) continue;
			slot.response = Fallback(current, slot);
		}
		if (!Commit(current)) {
			for (Slot slot : current.slots) {
				if (slot.forceSwitch && slot.response instanceof SwitchActionResponse && slot.switchTo != null) continue;
				slot.response = PassActionResponse.INSTANCE;
				slot.spent = null;
				slot.itemTarget = null;
				slot.switchTo = null;
			}
			if (!Commit(current)) {
				Wipe(current.actor);
				Clear();
			}
		}
	}

	public static boolean Hold(AIBattleActor actor) {
		if (actor == null || !BattleWatch.Owns(actor)) return false;
		if (Ended(actor)) return false;
		if (!BattleWatch.Connected()) return false;
		ShowdownActionRequest request = actor.getRequest();
		if (request == null || request.getWait()) return false;
		if (pending != null && pending.actor == actor && pending.request == request) return true;
		if (pending != null) BattleWatch.DropChoice(pending.id);
		ClearSwitchFlags(actor);
		Pending next = Pending.From(actor, request);
		if (next.slots.isEmpty()) return false;
		pending = next;
		ticks = 0;
		PassEmptySwitches(next);
		if (FirstOpen(next) == null) {
			pending = next;
			if (!Commit(next)) {
				Wipe(actor);
				Clear();
				return false;
			}
			return true;
		}
		BattleWatch.Wake("your_turn", TurnText(next), next.id);
		return true;
	}

	public static List<Map<String, Object>> Actions(String target) {
		Pending current = pending;
		if (current == null) return List.of();
		if (!target.isBlank()) {
			BattlePokemon mon = BattleWatch.FindMon(target);
			BagItem item = FindItem(target);
			if (mon == null && item == null) return null;
			List<Map<String, Object>> actions = new ArrayList<>();
			if (mon != null && CanSwitch(current) && CanSend(current, mon)) actions.add(SwitchAction(current, mon));
			if (item != null && CanOfferItem(current)) actions.add(ItemAction(current, item));
			return actions;
		}
		List<Map<String, Object>> actions = new ArrayList<>();
		Slot slot = FirstOpen(current);
		if (slot != null && !slot.forceSwitch && slot.moveset != null) {
			for (InBattleMove move : slot.moveset.getMoves()) {
				if (move == null || !move.canBeUsed()) continue;
				if (move.mustBeUsed()) {
					actions.clear();
					actions.add(MoveAction(current, slot, move));
					break;
				}
				actions.add(MoveAction(current, slot, move));
			}
		}
		if (CanSwitch(current)) {
			for (BattlePokemon mon : BattleWatch.Party()) {
				if (!CanSend(current, mon)) continue;
				actions.add(SwitchAction(current, mon));
			}
		}
		return actions;
	}

	public static List<Map<String, Object>> Items() {
		Pending current = pending;
		TrainerBag bag = Bag(current == null ? BattleWatch.OurActor() : current.actor);
		if (bag == null || (current != null && !CanOfferItem(current))) return List.of();
		List<Map<String, Object>> items = new ArrayList<>();
		for (BagItem item : bag.getItems()) {
			if (item == null) continue;
			int queued = current == null ? 0 : QueuedNamed(current, item.getItemName());
			int count = bag.getQuanity(item) - queued;
			if (count <= 0) continue;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", item.getItemName());
			row.put("name", item.getItemName());
			row.put("count", count);
			items.add(row);
		}
		return items;
	}

	public static Map<String, Object> Act(String action, String target, String payload) {
		Pending current = pending;
		if (current == null) return Fail("It is not your turn.");
		if (Ended(current.actor)) {
			Clear();
			return Fail("It is not your turn.");
		}
		if (!ChoiceMatches(current, payload)) {
			return Fail("That turn has passed. The current choice is " + current.id + ".");
		}
		Slot slot = FirstOpen(current);
		if (slot == null) return Fail("The choice is already in.");
		String verb = action.toLowerCase(Locale.ROOT);
		ShowdownActionResponse response;
		BagItem spent = null;
		try {
			if (verb.equals("switch_out")) {
				if (!CanSwitch(current)) return Fail("You cannot switch right now.");
				BattlePokemon mon = BattleWatch.FindMon(target);
				if (mon == null) return Fail("Unknown Pokemon: " + target + ".");
				if (!CanSend(current, mon)) return Fail(BattleWatch.MonLabel(mon) + " cannot be sent out.");
				response = new SwitchActionResponse(mon.getUuid());
			} else if (verb.equals("use_item")) {
				if (slot.forceSwitch) return Fail("You have to switch.");
				if (!current.actor.canFitForcedAction() || QueuedItems(current) > 0) return Fail("An item is already chosen this turn.");
				if (!ItemsAllowed(current)) return Fail("No items can be used.");
				BagItem item = FindItem(target);
				if (item == null) return Fail("Unknown item: " + target + ".");
				BattlePokemon onto = ItemTarget(current, payload);
				if (onto == null) return Fail("That Pokemon is not in the party.");
				if (!item.canUse(ItemStackOf(item), current.actor.getBattle(), onto)) {
					return Fail("That item cannot be used on " + BattleWatch.MonLabel(onto) + ".");
				}
				response = new ForcePassActionResponse();
				spent = item;
				slot.itemTarget = onto;
			} else {
				if (slot.forceSwitch) return Fail("You have to switch.");
				InBattleMove move = FindMove(slot, verb);
				if (move == null) return Fail("Unknown move: " + action + ". Call list_actions.");
				response = LegalMove(slot, move);
				if (response == null) return Fail("That move cannot be used right now.");
			}
			if (!(response instanceof ForcePassActionResponse) && !response.isValid(slot.active, slot.moveset, slot.forceSwitch)) {
				return Fail("That choice is not legal right now.");
			}
		} catch (RuntimeException err) {
			CobbleAGPI.LOGGER.warn("Could not build a battle choice: {}", err.toString());
			return Fail("That choice could not be sent.");
		}
		slot.response = response;
		slot.spent = spent;
		if (response instanceof SwitchActionResponse) slot.switchTo = BattleWatch.FindMon(target);
		PassEmptySwitches(current);
		if (FirstOpen(current) == null && !Commit(current)) {
			slot.response = null;
			slot.spent = null;
			slot.itemTarget = null;
			slot.switchTo = null;
			return Fail("That choice was rejected. Call list_actions.");
		}
		return Accepted(current, verb);
	}

	private static boolean Commit(Pending current) {
		if (pending != current) return false;
		if (Ended(current.actor)) {
			Wipe(current.actor);
			pending = null;
			ticks = 0;
			return true;
		}
		Wipe(current.actor);
		for (Slot slot : current.slots) {
			if (slot.spent == null || slot.itemTarget == null) continue;
			if (!current.actor.canFitForcedAction()) {
				CobbleAGPI.LOGGER.warn("The battle cannot take another item.");
				Wipe(current.actor);
				return false;
			}
			current.actor.forceChoose(new BagItemActionResponse(slot.spent, slot.itemTarget, slot.itemTarget.getUuid().toString()));
		}
		List<ShowdownActionResponse> responses = new ArrayList<>();
		for (Slot slot : current.slots) {
			responses.add(slot.response == null ? PassActionResponse.INSTANCE : slot.response);
		}
		try {
			current.actor.setActionResponses(responses);
		} catch (RuntimeException err) {
			CobbleAGPI.LOGGER.warn("Battle rejected the choice: {}", err.toString());
			Wipe(current.actor);
			return false;
		}
		TrainerBag bag = Bag(current.actor);
		if (bag != null) {
			for (Slot slot : current.slots) {
				if (slot.spent != null) bag.use(slot.spent);
			}
		}
		ClearSwitchFlags(current.actor);
		lastSent = current.id;
		BattleWatch.DropChoice(current.id);
		pending = null;
		ticks = 0;
		return true;
	}

	private static void Wipe(BattleActor actor) {
		if (actor == null) return;
		actor.getResponses().clear();
		actor.getExpectingPassActions().clear();
	}

	private static ShowdownActionResponse Fallback(Pending current, Slot slot) {
		if (slot.forceSwitch) {
			for (BattlePokemon mon : BattleWatch.Party()) {
				if (!CanSend(current, mon)) continue;
				SwitchActionResponse response = new SwitchActionResponse(mon.getUuid());
				if (slot.active != null && response.isValid(slot.active, slot.moveset, true)) {
					slot.switchTo = mon;
					return response;
				}
			}
			return PassActionResponse.INSTANCE;
		}
		if (slot.moveset != null) {
			for (InBattleMove move : slot.moveset.getMoves()) {
				if (move == null || !move.canBeUsed()) continue;
				ShowdownActionResponse response = LegalMove(slot, move);
				if (response != null) return response;
			}
		}
		return PassActionResponse.INSTANCE;
	}

	private static Map<String, Object> Accepted(Pending current, String verb) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("action", verb);
		body.put("accepted", true);
		Slot next = FirstOpen(current);
		if (next != null) {
			int open = OpenCount(current);
			String who = SlotName(next);
			body.put("remaining", open);
			body.put("next", who);
			body.put("text", "Another choice is still open for " + who + ". Call list_actions and send_action again with payload \"" + current.id + "\".");
		}
		return body;
	}

	private static String TurnText(Pending next) {
		int open = OpenCount(next);
		int forced = ForcedCount(next);
		String tail = " This is a new choice. Choice " + next.id + ". " + open + " choice" + (open == 1 ? "" : "s")
			+ " to make. send_action payload is \"" + next.id + "\".";
		if (forced > 0) {
			String who = forced == 1 ? "Your active Pokemon" : forced + " of your active Pokemon";
			return who + " fainted. You must switch_out before the battle can go on. The player's battle menu stays hidden until you do." + tail;
		}
		return "It is your turn as " + BattleWatch.LockedName() + "." + tail + " The first open slot is " + SlotName(FirstOpen(next)) + ".";
	}

	private static int ForcedCount(Pending next) {
		int count = 0;
		for (Slot slot : next.slots) {
			if (slot.response == null && slot.forceSwitch) count++;
		}
		return count;
	}

	private static int OpenCount(Pending current) {
		int open = 0;
		for (Slot slot : current.slots) {
			if (slot.response == null) open++;
		}
		return open;
	}

	private static String SlotName(Slot slot) {
		if (slot != null && slot.forceSwitch && slot.active != null && slot.active.getBattlePokemon() == null) return "the fainted Pokemon's slot";
		if (slot == null || slot.active == null || slot.active.getBattlePokemon() == null) return "the next Pokemon";
		return BattleWatch.MonLabel(slot.active.getBattlePokemon());
	}

	private static boolean Ended(BattleActor actor) {
		PokemonBattle battle = actor == null ? null : actor.getBattle();
		return battle == null || battle.getEnded();
	}

	private static boolean ChoiceMatches(Pending current, String payload) {
		if (payload == null || payload.isBlank()) return false;
		return payload.trim().split("\\s+")[0].equals(String.valueOf(current.id));
	}

	private static String ExtraPayload(String payload) {
		if (payload == null) return "";
		String[] parts = payload.trim().split("\\s+");
		return parts.length > 1 ? parts[1] : "";
	}

	private static boolean CanSwitch(Pending current) {
		Slot slot = FirstOpen(current);
		return slot != null && (slot.forceSwitch || slot.moveset == null || !slot.moveset.getTrapped());
	}

	private static Slot FirstOpen(Pending current) {
		for (Slot slot : current.slots) {
			if (slot.response == null) return slot;
		}
		return null;
	}

	private static void PassEmptySwitches(Pending current) {
		boolean any = false;
		for (BattlePokemon mon : BattleWatch.Party()) {
			if (CanSend(current, mon)) {
				any = true;
				break;
			}
		}
		if (any) return;
		for (Slot slot : current.slots) {
			if (slot.response != null || !slot.forceSwitch) continue;
			slot.response = PassActionResponse.INSTANCE;
		}
	}

	private static void ClearSwitchFlags(AIBattleActor actor) {
		for (BattlePokemon mon : actor.getPokemonList()) mon.setWillBeSwitchedIn(false);
	}

	private static boolean CanOfferItem(Pending current) {
		Slot slot = FirstOpen(current);
		if (slot == null || slot.forceSwitch) return false;
		if (!current.actor.canFitForcedAction() || QueuedItems(current) > 0) return false;
		return ItemsAllowed(current);
	}

	private static boolean CanSend(Pending current, BattlePokemon mon) {
		if (mon == null || !mon.canBeSentOut()) return false;
		if (IsActive(current, mon) || AlreadySwitching(current, mon)) return false;
		return true;
	}

	private static boolean AlreadySwitching(Pending current, BattlePokemon mon) {
		for (Slot slot : current.slots) {
			if (slot.switchTo != null && slot.switchTo.getUuid().equals(mon.getUuid())) return true;
		}
		return false;
	}

	private static int QueuedItems(Pending current) {
		int count = 0;
		for (Slot slot : current.slots) {
			if (slot.spent != null) count++;
		}
		return count;
	}

	private static boolean IsActive(Pending current, BattlePokemon mon) {
		for (Slot slot : current.slots) {
			if (slot.active == null) continue;
			BattlePokemon active = slot.active.getBattlePokemon();
			if (active != null && active.getUuid().equals(mon.getUuid())) return true;
		}
		return false;
	}

	private static boolean ItemsAllowed(Pending current) {
		BattleState state = BattleState.findFirst(current.actor.getBattle());
		if (state == null || state.getRules() == null) return true;
		int max = state.getRules().getMaxItemUses();
		if (max < 0) return true;
		BattleState.ActorState actorState = state.getState(current.actor.getUuid());
		int used = actorState == null ? 0 : actorState.getItemsUsed();
		return used + QueuedItems(current) < max;
	}

	private static TrainerBag Bag(BattleActor actor) {
		if (actor instanceof TrainerEntityBattleActor trainer) return trainer.getBag();
		return null;
	}

	private static BagItem FindItem(String target) {
		if (target == null || target.isBlank()) return null;
		TrainerBag bag = Bag(pending == null ? BattleWatch.OurActor() : pending.actor);
		if (bag == null) return null;
		int queued = pending == null ? 0 : QueuedNamed(pending, target);
		for (BagItem item : bag.getItems()) {
			if (item != null && target.equalsIgnoreCase(item.getItemName()) && bag.getQuanity(item) - queued > 0) return item;
		}
		return null;
	}

	private static int QueuedNamed(Pending current, String name) {
		int count = 0;
		for (Slot slot : current.slots) {
			if (slot.spent != null && name.equalsIgnoreCase(slot.spent.getItemName())) count++;
		}
		return count;
	}

	private static ItemStack ItemStackOf(BagItem item) {
		ResourceLocation id = ResourceLocation.tryParse(item.getItemName());
		if (id == null) return ItemStack.EMPTY;
		var found = BuiltInRegistries.ITEM.get(id);
		if (found == null || found == Items.AIR) return ItemStack.EMPTY;
		return found.getDefaultInstance();
	}

	private static BattlePokemon ItemTarget(Pending current, String payload) {
		String extra = ExtraPayload(payload);
		if (!extra.isBlank()) return BattleWatch.FindMon(extra);
		Slot slot = FirstOpen(current);
		return slot == null || slot.active == null ? null : slot.active.getBattlePokemon();
	}

	private static InBattleMove FindMove(Slot slot, String action) {
		if (slot.moveset == null) return null;
		for (InBattleMove move : slot.moveset.getMoves()) {
			if (move == null || !move.canBeUsed()) continue;
			if (action.equalsIgnoreCase(MoveId(move)) || action.equalsIgnoreCase(move.getMove())) return move;
		}
		return null;
	}

	private static String MoveId(InBattleMove move) {
		if (move.getId() != null && !move.getId().isBlank()) return move.getId();
		return move.getMove() == null ? "move" : move.getMove();
	}

	private static ShowdownActionResponse LegalMove(Slot slot, InBattleMove move) {
		String id = MoveId(move);
		List<Targetable> targets = move.getTargets(slot.active);
		if (targets != null) {
			for (Targetable target : targets) {
				MoveActionResponse candidate = new MoveActionResponse(id, target.getPNX(), null);
				if (candidate.isValid(slot.active, slot.moveset, slot.forceSwitch)) return candidate;
			}
		}
		MoveActionResponse plain = new MoveActionResponse(id, null, null);
		return plain.isValid(slot.active, slot.moveset, slot.forceSwitch) ? plain : null;
	}

	private static String PayloadHow(Pending current, String action, String target) {
		String how = "send_action with action \"" + action + "\"";
		if (target != null && !target.isBlank()) how += " and target \"" + target + "\"";
		return how + " and payload \"" + current.id + "\".";
	}

	private static Map<String, Object> MoveAction(Pending current, Slot slot, InBattleMove move) {
		Map<String, Object> row = new LinkedHashMap<>();
		String id = MoveId(move);
		row.put("name", id);
		row.put("how", PayloadHow(current, id, ""));
		row.put("when", "Use this move for " + SlotName(slot) + ".");
		row.put("pp", move.getPp());
		row.put("max_pp", move.getMaxpp());
		Move known = KnownMove(slot, move);
		if (known != null) {
			if (known.getType() != null && known.getType().getDisplayName() != null) {
				row.put("type", known.getType().getDisplayName().getString());
			}
			if (known.getTemplate() != null && known.getTemplate().getDamageCategory() != null) {
				row.put("category", known.getTemplate().getDamageCategory().getName());
			}
			row.put("power", known.getPower());
			row.put("accuracy", known.getAccuracy());
			if (known.getDescription() != null) {
				String effect = known.getDescription().getString();
				if (effect != null && effect.length() > 180) effect = effect.substring(0, 180);
				if (effect != null && !effect.isBlank()) row.put("effect", effect);
			}
		}
		return row;
	}

	private static Move KnownMove(Slot slot, InBattleMove move) {
		if (slot.active == null) return null;
		BattlePokemon mon = slot.active.getBattlePokemon();
		if (mon == null || mon.getMoveSet() == null) return null;
		for (Move known : mon.getMoveSet().getMoves()) {
			if (known == null) continue;
			if (known.getName().equalsIgnoreCase(MoveId(move)) || known.getName().equalsIgnoreCase(move.getMove())) return known;
		}
		return null;
	}

	private static Map<String, Object> SwitchAction(Pending current, BattlePokemon mon) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", "switch_out");
		row.put("target", mon.getUuid().toString());
		row.put("how", PayloadHow(current, "switch_out", mon.getUuid().toString()));
		row.put("when", "Send in " + BattleWatch.MonLabel(mon) + ".");
		return row;
	}

	private static Map<String, Object> ItemAction(Pending current, BagItem item) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", "use_item");
		row.put("target", item.getItemName());
		row.put("how", PayloadHow(current, "use_item", item.getItemName()) + " To use it on a benched Pokemon, add that Pokemon's id after the choice number.");
		row.put("when", "Use this bag item.");
		return row;
	}

	private static Map<String, Object> Fail(String error) {
		return Map.of("ok", false, "error", error);
	}

	private static final class Slot {
		final ActiveBattlePokemon active;
		final ShowdownMoveset moveset;
		final boolean forceSwitch;
		ShowdownActionResponse response;
		BagItem spent;
		BattlePokemon itemTarget;
		BattlePokemon switchTo;
		Slot(ActiveBattlePokemon active, ShowdownMoveset moveset, boolean forceSwitch) {
			this.active = active;
			this.moveset = moveset;
			this.forceSwitch = forceSwitch;
		}
	}

	private static final class Pending {
		final int id;
		final int limitTicks;
		final AIBattleActor actor;
		final ShowdownActionRequest request;
		final List<Slot> slots = new ArrayList<>();
		boolean shown;

		Pending(AIBattleActor actor, ShowdownActionRequest request) {
			this.id = nextChoice++;
			this.limitTicks = ModConfig.MoveSeconds() * 20;
			this.actor = actor;
			this.request = request;
		}

		static Pending From(AIBattleActor actor, ShowdownActionRequest request) {
			Pending next = new Pending(actor, request);
			List<ActiveBattlePokemon> actives = actor.getActivePokemon();
			List<ShowdownMoveset> movesets = request.getActive();
			List<Boolean> forces = request.getForceSwitch();
			int count = actives == null ? 0 : actives.size();
			for (int i = 0; i < count; i++) {
				ActiveBattlePokemon active = actives != null && i < actives.size() ? actives.get(i) : null;
				boolean force = forces != null && i < forces.size() && Boolean.TRUE.equals(forces.get(i));
				ShowdownMoveset moveset = movesets != null && i < movesets.size() ? movesets.get(i) : null;
				BattlePokemon mon = active == null ? null : active.getBattlePokemon();
				Slot slot = new Slot(active, moveset, force);
				boolean fainted = mon != null && mon.getHealth() <= 0;
				// Cobblemon empties a slot when its Pokemon faints, so a forced switch has no mon here.
				if (active == null || (!force && (mon == null || moveset == null || fainted))) {
					slot.response = PassActionResponse.INSTANCE;
				}
				next.slots.add(slot);
			}
			return next;
		}
	}
}

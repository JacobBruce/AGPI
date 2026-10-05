package bitfreak.cobbleagpi.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import bitfreak.cobbleagpi.TurnControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AIBattleActor.class)
public class AiChoiceMixin {
	@Inject(method = "onChoiceRequested", at = @At("HEAD"), cancellable = true, remap = false)
	private void HoldForAgent(CallbackInfo ci) {
		if (TurnControl.Hold((AIBattleActor) (Object) this)) ci.cancel();
	}
}

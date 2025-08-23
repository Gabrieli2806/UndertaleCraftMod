package com.g2806.undertaleextinct.mixin;

import com.g2806.undertaleextinct.UndertaleExtinct;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public class SpawnHelperMixin {

    // Simple approach - intercept addEntity which all spawning goes through
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void preventExtinctMobAdding(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof MobEntity && UndertaleExtinct.isMobPurged(entity.getType())) {
            cir.setReturnValue(false);
        }
    }
}
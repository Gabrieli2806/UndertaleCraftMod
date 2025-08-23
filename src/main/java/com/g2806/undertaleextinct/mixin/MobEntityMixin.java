package com.g2806.undertaleextinct.mixin;

import com.g2806.undertaleextinct.UndertaleExtinct;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEntity.class)
public class MobEntityMixin {

    // Intercept the basic tick method that all entities have
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void preventExtinctMobTicking(CallbackInfo ci) {
        MobEntity self = (MobEntity)(Object)this;
        if (UndertaleExtinct.isMobPurged(self.getType())) {
            self.discard();
            ci.cancel();
        }
    }
}
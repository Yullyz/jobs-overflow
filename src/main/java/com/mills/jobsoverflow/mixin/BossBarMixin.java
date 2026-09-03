package com.mills.jobsoverflow.mixin;

import com.mills.jobsoverflow.client.JobsOverflowClient;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BossBar.class)
public abstract class BossBarMixin {
    @Inject(method = "setName", at = @At("HEAD"))
    private void jobsOverflow$onNameChanged(Text name, CallbackInfo ci) {
        JobsOverflowClient.onBossBarName((BossBar) (Object) this, name.getString());
    }

    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void jobsOverflow$overrideName(CallbackInfoReturnable<Text> cir) {
        Text override = JobsOverflowClient.getOverrideName((BossBar) (Object) this);
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
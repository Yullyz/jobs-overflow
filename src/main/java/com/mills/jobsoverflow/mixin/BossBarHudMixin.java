package com.mills.jobsoverflow.mixin;

import com.mills.jobsoverflow.client.JobsOverflowClient;
import com.mills.jobsoverflow.client.JobsOverflowClient.ParsedJob;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Minecraft's own boss-bar renderer intact so the Jobs bar has the exact
 * vanilla boss-bar textures, colours, segmentation/notches and scaling.
 * We only add the overflow readout underneath it.
 */
@Mixin(BossBarHud.class)
public abstract class BossBarHudMixin {

    @Inject(
            method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void jobsOverflow$cosmeticOverride(DrawContext context, int x, int y, BossBar bossBar, CallbackInfo ci) {
        if (!"levels".equals(JobsOverflowClient.getDisplayMode())) {
            return;
        }

        JobsOverflowClient.ParsedJob job = JobsOverflowClient.parseFromBar(bossBar);
        if (job == null || job.level() < JobsOverflowClient.MAX_LEVEL) {
            return;
        }

        double overflow = JobsOverflowClient.getOverflow(job.job());
        JobsOverflowClient.VirtualLevel virtual = JobsOverflowClient.computeVirtualLevel(job.maxXp(), overflow);

        MinecraftClient client = MinecraftClient.getInstance();
        int centerX = client.getWindow().getScaledWidth() / 2;

        int barWidth = 182;
        int barHeight = 5;
        int left = centerX - barWidth / 2;
        int yOffset = -1; // pixels to shift the bar upward
        y = y - yOffset;

        float progress = virtual.xpForLevel() <= 0 ? 0f : (float) (virtual.xpInto() / virtual.xpForLevel());
        progress = Math.max(0f, Math.min(1f, progress));
        int filledWidth = (int) (barWidth * progress);

        // border (dark gray bevel, 2px)
        context.fill(left - 2, y - 2, left + barWidth + 2, y + barHeight + 2, 0xFF1E1E1E);
        context.fill(left - 1, y - 1, left + barWidth + 1, y + barHeight + 1, 0xFF3C3C3C);

        // unfilled background
        context.fill(left, y, left + barWidth, y + barHeight, 0xFF2A2A2A);

        // filled portion drawn as a gradient (dark -> light -> dark across the bar)
        int[] gradient = JobsOverflowClient.getJobGradient(job.job());
        for (int i = 0; i < filledWidth; i++) {
            float t = barWidth <= 1 ? 0f : (float) i / (barWidth - 1);
            int color = jobsOverflow$gradientColorAt(t, gradient[0], gradient[1]);
            context.fill(left + i, y, left + i + 1, y + barHeight, color);
        }

        ci.cancel();
    }

    private static int jobsOverflow$gradientColorAt(float t, int base, int light) {
        float local;
        int c1, c2;
        if (t < 0.5f) {
            local = t / 0.5f;
            c1 = base;
            c2 = light;
        } else {
            local = (t - 0.5f) / 0.5f;
            c1 = light;
            c2 = base;
        }
        int r = jobsOverflow$lerp((c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF, local);
        int g = jobsOverflow$lerp((c1 >> 8) & 0xFF, (c2 >> 8) & 0xFF, local);
        int b = jobsOverflow$lerp(c1 & 0xFF, c2 & 0xFF, local);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int jobsOverflow$lerp(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }


    @Inject(
            method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;)V",
            at = @At("RETURN")
    )
    private void jobsOverflow$drawOverflow(DrawContext context, int x, int y, BossBar bossBar, CallbackInfo ci) {
        if (!"xp".equals(JobsOverflowClient.getDisplayMode())) {
            return;
        }
        ParsedJob job = JobsOverflowClient.parse(bossBar.getName().getString());
        if (job == null) {
            return;
        }

        double overflow = JobsOverflowClient.getOverflow(job.job());
        if (job.level() < JobsOverflowClient.MAX_LEVEL && overflow <= 0) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        int centerX = client.getWindow().getScaledWidth() / 2;

        // Vanilla renders the boss bar at y and its title above it. Put the
        // overflow line directly below the bar, matching vanilla text style.
        String text = "Overflow: " + JobsOverflowClient.formatNumber(overflow) + " XP";
        context.drawCenteredTextWithShadow(textRenderer, text, centerX, y + 7, 0xFFFFFFFF);
    }
}

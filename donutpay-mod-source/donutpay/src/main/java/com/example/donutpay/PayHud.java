package com.example.donutpay;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class PayHud implements HudElement {
    /** Payments below this show nothing at all (no popup, no sound). */
    public static final double MIN_POPUP = 10_000_000d;
    /** Payments at or above this trigger the big effect. */
    public static final double MEGA_THRESHOLD = 100_000_000d;

    private static final long TOAST_LIFE = 5000, MEGA_TOAST_LIFE = 7500, SLIDE = 350, MEGA_FX = 4200;

    private static final SoundEvent PAY_SOUND  = SoundEvent.of(Identifier.of(DonutPayClient.MOD_ID, "pay"));
    private static final SoundEvent MEGA_SOUND = SoundEvent.of(Identifier.of(DonutPayClient.MOD_ID, "mega"));

    private record Toast(String name, double amount, boolean mega, long start, SkinTextures skin) {
        long life() { return mega ? MEGA_TOAST_LIFE : TOAST_LIFE; }
    }
    private static final class Piece { float x, y, vx, vy, size; int color; }

    private static final List<Toast> TOASTS = new ArrayList<>();
    private static final List<Piece> CONFETTI = new ArrayList<>();
    private static final Random RNG = new Random();

    private static long megaStart = -1, lastFrame = 0;
    private static boolean confettiSpawned = false;
    private static double megaAmount;
    private static String megaName = "";

    // ---------- called when a payment is detected ----------
    public static void push(String name, double amount) {
        if (amount < MIN_POPUP) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            boolean mega = amount >= MEGA_THRESHOLD;
            long now = Util.getMeasuringTimeMs();
            TOASTS.add(0, new Toast(name, amount, mega, now, lookupSkin(mc, name)));
            while (TOASTS.size() > 5) TOASTS.remove(TOASTS.size() - 1);

            if (mega) {
                megaStart = now; megaName = name; megaAmount = amount; confettiSpawned = false;
                mc.getSoundManager().play(PositionedSoundInstance.master(MEGA_SOUND, 1.0f, 1.0f));
            } else {
                mc.getSoundManager().play(PositionedSoundInstance.master(PAY_SOUND, 1.0f, 1.0f));
            }
        });
    }

    private static SkinTextures lookupSkin(MinecraftClient mc, String name) {
        if (mc.getNetworkHandler() != null) {
            String clean = name.replaceFirst("^[.*]", "");
            PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(clean);
            if (e != null) return e.getSkinTextures();
        }
        // Not in the tab list -> default skin (Steve/Alex) so nothing breaks.
        return DefaultSkinHelper.getSkinTextures(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    // ---------- rendering ----------
    @Override
    public void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();

        if (megaStart >= 0) {
            if (now - megaStart > MEGA_FX) { megaStart = -1; CONFETTI.clear(); }
            else renderMegaEffect(ctx, mc.textRenderer, now, dt, sw, sh);
        }

        TOASTS.removeIf(t -> now - t.start() > t.life());
        int y = 8;
        for (Toast t : TOASTS) {
            y += renderToast(ctx, mc.textRenderer, t, now, sw, y) + 6;
        }
    }

    private int renderToast(DrawContext c, TextRenderer tr, Toast t, long now, int sw, int y) {
        int w = t.mega() ? 200 : 165, h = t.mega() ? 46 : 36;
        long age = now - t.start(), left = t.life() - age;
        float in = ease(Math.min(1f, age / (float) SLIDE));
        float out = left < SLIDE ? ease(left / (float) SLIDE) : 1f;
        float p = Math.min(in, out);
        int x = sw - w - 8 + Math.round((1f - p) * (w + 16));
        if (t.mega()) x += Math.round((float) Math.sin(age / 25.0) * (age < 600 ? 3 : 0)); // shake on arrival

        c.fill(x, y, x + w, y + h, 0xE0121218);
        int border = t.mega() ? Color.HSBtoRGB((now % 1500) / 1500f, 0.85f, 1f) : 0xFF55FF55;
        frame(c, x, y, w, h, t.mega() ? 2 : 1, border);

        int head = h - 12;
        PlayerSkinDrawer.draw(c, t.skin(), x + 6, y + 6, head);
        int tx = x + head + 12;
        c.drawText(tr, t.name(), tx, y + 7, 0xFFFFFFFF, true);
        c.drawText(tr, "+" + DonutPayClient.formatAmount(t.amount()), tx, y + 19, t.mega() ? 0xFFFFD700 : 0xFF55FF55, true);
        if (t.mega()) c.drawText(tr, "MEGA PAYMENT", tx, y + 31, border, true);
        return h;
    }

    private void renderMegaEffect(DrawContext c, TextRenderer tr, long now, float dt, int sw, int sh) {
        long t = now - megaStart;
        float fadeOut = t > MEGA_FX - 800 ? Math.max(0f, (MEGA_FX - t) / 800f) : 1f;

        // 1) gold flash at the start
        if (t < 450) {
            int a = (int) (170 * (1f - t / 450f));
            c.fill(0, 0, sw, sh, (a << 24) | 0xFFD700);
        }

        // 2) pulsing golden/rainbow glow around the whole screen
        float pulse = 0.65f + 0.35f * (float) Math.sin(t / 130.0);
        int rgb = Color.HSBtoRGB((t % 2500) / 2500f, 0.55f, 1f) & 0xFFFFFF;
        for (int i = 0; i < 14; i++) {
            float f = 1f - i / 14f;
            int a = (int) (150 * f * f * pulse * fadeOut);
            if (a > 2) frame(c, i * 3, i * 3, sw - i * 6, sh - i * 6, 3, (a << 24) | rgb);
        }

        // 3) confetti rain
        if (!confettiSpawned) {
            confettiSpawned = true;
            for (int i = 0; i < 220; i++) {
                Piece p = new Piece();
                p.x = RNG.nextFloat() * sw; p.y = -RNG.nextFloat() * sh * 0.8f - 10;
                p.vx = (RNG.nextFloat() - 0.5f) * 90; p.vy = 90 + RNG.nextFloat() * 220;
                p.size = 2 + RNG.nextFloat() * 3;
                p.color = Color.HSBtoRGB(RNG.nextFloat(), 0.8f, 1f);
                CONFETTI.add(p);
            }
        }
        for (Piece p : CONFETTI) {
            p.x += p.vx * dt; p.y += p.vy * dt; p.vx += (RNG.nextFloat() - 0.5f) * 60 * dt;
            if (p.y > sh) { p.y = -5; p.x = RNG.nextFloat() * sw; }
            int a = (int) (255 * fadeOut);
            if (a > 8) c.fill((int) p.x, (int) p.y, (int) (p.x + p.size), (int) (p.y + p.size * 1.6f), (a << 24) | (p.color & 0xFFFFFF));
        }

        // 4) big pop-in title + amount
        float pop = easeOutBack(Math.min(1f, t / 550f));
        int ta = (int) (255 * fadeOut);
        if (ta > 8) {
            int gold = (ta << 24) | 0xFFD700, white = (ta << 24) | 0xFFFFFF;
            String title = "MEGA PAYMENT!";
            String sub = megaName + " paid you " + DonutPayClient.formatAmount(megaAmount);
            var m = c.getMatrices();
            m.pushMatrix();
            m.translate(sw / 2f, sh * 0.25f);
            m.scale(4f * pop, 4f * pop);
            c.drawText(tr, title, -tr.getWidth(title) / 2, -4, gold, true);
            m.popMatrix();
            m.pushMatrix();
            m.translate(sw / 2f, sh * 0.25f + 34f * pop);
            m.scale(1.8f * pop, 1.8f * pop);
            c.drawText(tr, sub, -tr.getWidth(sub) / 2, 0, white, true);
            m.popMatrix();
        }
    }

    // ---------- helpers ----------
    private static void frame(DrawContext c, int x, int y, int w, int h, int th, int color) {
        c.fill(x, y, x + w, y + th, color);
        c.fill(x, y + h - th, x + w, y + h, color);
        c.fill(x, y + th, x + th, y + h - th, color);
        c.fill(x + w - th, y + th, x + w, y + h - th, color);
    }
    private static float ease(float t) { float u = 1f - t; return 1f - u * u * u; }
    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}

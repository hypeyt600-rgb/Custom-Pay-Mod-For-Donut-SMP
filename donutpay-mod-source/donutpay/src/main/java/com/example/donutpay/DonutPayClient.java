package com.example.donutpay;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.util.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonutPayClient implements ClientModInitializer {
    public static final String MOD_ID = "donutpay";

    // Matches "Steve paid you $2.5M" at the START of a server message.
    // Anchored on purpose so players can't fake it by typing it in public chat
    // (public chat is usually "Rank Name » message"). If DonutSMP changes the
    // wording, this is the only line you need to edit.
    private static final Pattern PAID_YOU = Pattern.compile(
        "^\\W*?([.*]?\\w{3,16})\\s+paid you\\s+\\$?([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT])?(?!\\w)",
        Pattern.CASE_INSENSITIVE);

    @Override
    public void onInitializeClient() {
        HudElementRegistry.addLast(Identifier.of(MOD_ID, "pay_hud"), new PayHud());

        // Only system/game messages (the server's own formatted lines), never signed player chat.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            Matcher m = PAID_YOU.matcher(message.getString().trim());
            if (!m.find()) return;
            double amount = parseAmount(m.group(2), m.group(3));
            PayHud.push(m.group(1), amount);
        });

        // /donutpaytest <name> <amount>  e.g. /donutpaytest Notch 150m
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("donutpaytest")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            String raw = StringArgumentType.getString(ctx, "amount").toLowerCase();
                            String suffix = raw.matches(".*[kmbt]$") ? raw.substring(raw.length() - 1) : "";
                            String num = suffix.isEmpty() ? raw : raw.substring(0, raw.length() - 1);
                            PayHud.push(name, parseAmount(num, suffix));
                            return 1;
                        })))));
    }

    public static double parseAmount(String number, String suffix) {
        double v = Double.parseDouble(number.replace(",", ""));
        if (suffix == null) return v;
        return switch (suffix.toLowerCase()) {
            case "k" -> v * 1_000d;
            case "m" -> v * 1_000_000d;
            case "b" -> v * 1_000_000_000d;
            case "t" -> v * 1_000_000_000_000d;
            default -> v;
        };
    }

    public static String formatAmount(double v) {
        String[] units = {"", "K", "M", "B", "T"};
        int i = 0;
        while (v >= 1000 && i < units.length - 1) { v /= 1000; i++; }
        String s = (v == Math.floor(v)) ? String.valueOf((long) v) : String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return "$" + s + units[i];
    }
}

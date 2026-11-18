package com.example.auction;

import com.mojang.brigadier.arguments.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.regex.*;

public class AuctionMod implements ClientModInitializer {
    // "Name 5k", "<Name> bid 2.5m", "[Rank] Name: $1,000" -> bidder + amount
    static final Pattern BID = Pattern.compile(
        "^(?:<)?(?:\\[[^\\]]*\\]\\s*)*(\\w{3,16})(?:>|:|»)?\\s+(?:bid\\s+)?\\$?([\\d.,]+)\\s*([kKmMbB])?$");

    static boolean active;
    static String itemName = "";
    static ItemStack itemStack = ItemStack.EMPTY;
    static double topBid;
    static String topBidder = null;
    static long endTime;
    static boolean announceEnd = true;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(
            ClientCommandManager.literal("auction")
                .then(ClientCommandManager.literal("start")
                    .then(ClientCommandManager.argument("minbid", StringArgumentType.word())
                        .executes(c -> start(StringArgumentType.getString(c, "minbid"), 60))
                        .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(5, 3600))
                            .executes(c -> start(StringArgumentType.getString(c, "minbid"),
                                IntegerArgumentType.getInteger(c, "seconds"))))))
                .then(ClientCommandManager.literal("end").executes(c -> { end(); return 1; }))
                .then(ClientCommandManager.literal("cancel").executes(c -> { active = false; msg("Auction cancelled."); return 1; }))
                .then(ClientCommandManager.literal("status").executes(c -> { status(); return 1; }))
        ));

        // Listen to both signed chat and system/game messages (servers differ).
        ClientReceiveMessageEvents.GAME.register((m, overlay) -> { if (!overlay) handle(m.getString()); });
        ClientReceiveMessageEvents.CHAT.register((m, s, sender, p, t) -> handle(m.getString()));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (active && System.currentTimeMillis() >= endTime) end();
        });

        HudRenderCallback.EVENT.register((ctx, tick) -> {
            if (!active) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            int x = 6, y = 6, w = 190, h = 56;
            ctx.fill(x, y, x + w, y + h, 0xA0000000);
            ctx.drawItem(itemStack, x + 4, y + 4);
            ctx.drawText(mc.textRenderer, "AUCTION: " + itemName, x + 26, y + 8, 0xFFFFAA00, true);
            ctx.drawText(mc.textRenderer, "Top bid: $" + fmt(topBid), x + 6, y + 24, 0xFF55FF55, true);
            ctx.drawText(mc.textRenderer, "Bidder: " + (topBidder == null ? "none yet" : topBidder), x + 6, y + 34, 0xFFFFFFFF, true);
            long left = Math.max(0, (endTime - System.currentTimeMillis()) / 1000);
            ctx.drawText(mc.textRenderer, "Time left: " + left + "s", x + 6, y + 44, 0xFFAAAAAA, true);
        });
    }

    static int start(String min, int seconds) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) { msg("Hold the item/block you're auctioning."); return 0; }
        double m = parse(min, null);
        if (m < 0) { msg("Bad min bid. Use e.g. 500, 5k, 2.5m."); return 0; }
        itemStack = held.copy();
        itemName = held.getCount() + "x " + held.getName().getString();
        topBid = m; topBidder = null;
        endTime = System.currentTimeMillis() + seconds * 1000L;
        active = true;
        say("AUCTION: " + itemName + " | start $" + fmt(m) + " | " + seconds + "s | type your bid in chat (e.g. 5k)");
        return 1;
    }

    static void handle(String raw) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Matcher m = BID.matcher(raw.trim());
        if (!m.matches()) return;
        String name = m.group(1);
        if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) return; // ignore own messages
        double amt = parse(m.group(2), m.group(3));
        if (amt > topBid || (amt == topBid && topBidder == null && amt > 0)) {
            topBid = amt; topBidder = name;
            if (endTime - System.currentTimeMillis() < 5000) endTime = System.currentTimeMillis() + 5000; // anti-snipe
            msg(name + " leads with $" + fmt(amt));
        }
    }

    static void end() {
        if (!active) return;
        active = false;
        if (topBidder == null) say("AUCTION ENDED: " + itemName + " - no bids.");
        else say("SOLD: " + itemName + " to " + topBidder + " for $" + fmt(topBid) + "! Pay me with /pay " +
            MinecraftClient.getInstance().player.getName().getString() + " " + fmt(topBid));
    }

    static void status() {
        if (!active) { msg("No active auction."); return; }
        msg(itemName + ": $" + fmt(topBid) + " by " + (topBidder == null ? "nobody" : topBidder));
    }

    static double parse(String num, String suffix) {
        try {
            String s = num.replace(",", "");
            if (suffix == null && !s.isEmpty() && Character.isLetter(s.charAt(s.length() - 1))) {
                suffix = s.substring(s.length() - 1); s = s.substring(0, s.length() - 1);
            }
            double v = Double.parseDouble(s);
            if (suffix != null) switch (suffix.toLowerCase()) {
                case "k" -> v *= 1e3; case "m" -> v *= 1e6; case "b" -> v *= 1e9; default -> {}
            }
            return v;
        } catch (Exception e) { return -1; }
    }

    static String fmt(double v) {
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }
    static String trim(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.2f", d); }

    static void msg(String s) {
        MinecraftClient.getInstance().player.sendMessage(Text.literal("[Auction] " + s), false);
    }
    static void say(String s) { // sends public chat
        MinecraftClient.getInstance().player.networkHandler.sendChatMessage(s);
    }
}

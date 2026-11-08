package com.example;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import com.mojang.brigadier.context.CommandContext;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

public class ExampleMod implements ModInitializer {

    private static String n(CommandContext<ServerCommandSource> c) { return getString(c, "name"); }

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(DummyManager::tick);

        PlayerBlockBreakEvents.AFTER.register((w, pl, pos, st, be) -> {
            if (pl instanceof ServerPlayerEntity sp) DummyManager.onBreak(sp, pos);
        });
        UseBlockCallback.EVENT.register((pl, w, hand, hit) -> {
            if (pl instanceof ServerPlayerEntity sp && hand == Hand.MAIN_HAND) DummyManager.onUse(sp, hit);
            return ActionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((pl, w, hand, pos, dir) -> {
            if (pl instanceof ServerPlayerEntity sp) DummyManager.onSwing(sp);
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((pl, w, hand, e, hit) -> {
            if (pl instanceof ServerPlayerEntity sp) DummyManager.onSwing(sp);
            return ActionResult.PASS;
        });
        // Dummy-r dike takiye Shift + Right Click = dummy menu
        UseEntityCallback.EVENT.register((pl, w, hand, e, hit) -> {
            if (pl instanceof ServerPlayerEntity sp && hand == Hand.MAIN_HAND
                    && pl.isSneaking() && e instanceof DummyPlayer d) {
                DummyMenu.openDummy(sp, d.getName().getString());
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            dispatcher.register(literal("dummy")
                .then(literal("menu").executes(c -> {
                    DummyMenu.openList(c.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(literal("exit").executes(c -> {
                    DummyManager.exitPreview(c.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(literal("spawn").then(argument("name", word()).executes(c -> {
                    ServerCommandSource s = c.getSource();
                    DummyPlayer.spawn(s.getServer(), s.getServer().getOverworld(),
                        s.getPosition(), s.getRotation().y, n(c), null);
                    return 1;
                })))
                .then(literal("remove").then(argument("name", word()).executes(c -> {
                    DummyManager.remove(c.getSource().getServer(), n(c));
                    return 1;
                })))
                .then(literal("stop").then(argument("name", word()).executes(c -> {
                    boolean ok = DummyManager.stopRecord(c.getSource().getServer(), n(c), c.getSource().getPlayer());
                    if (!ok) c.getSource().sendFeedback(() -> Text.literal("Ei dummy record hocche na"), false);
                    return 1;
                })))
                .then(literal("armor").then(argument("name", word())
                    .then(argument("type", word()).executes(c -> {
                        DummyPlayer d = DummyManager.getDummy(c.getSource().getServer(), n(c));
                        if (d == null) return 0;
                        DummyManager.armor(d, getString(c, "type"));
                        return 1;
                    }))))
                .then(argument("name", word())
                    .then(literal("record").executes(c -> {
                        DummyManager.startRecord(c.getSource().getPlayerOrThrow(), n(c));
                        return 1;
                    }))
                    .then(literal("bot").executes(c -> {
                        DummyManager.toggleBot(c.getSource().getServer(), n(c), c.getSource().getPlayer());
                        return 1;
                    }))
                    .then(literal("preview")
                        .then(literal("start")
                            .executes(c -> {
                                DummyManager.startReplay(c.getSource().getServer(), n(c), 0, c.getSource().getPlayer());
                                return 1;
                            })
                            .then(argument("time", word()).executes(c -> {
                                int t = DummyManager.parseTicks(getString(c, "time"));
                                if (t < 0) {
                                    c.getSource().sendFeedback(() -> Text.literal("Somoy format: 30s, 1m, 1m30s"), false);
                                    return 0;
                                }
                                DummyManager.startReplay(c.getSource().getServer(), n(c), t, c.getSource().getPlayer());
                                return 1;
                            })))
                        .then(literal("stop").executes(c -> {
                            DummyManager.stopReplay(n(c));
                            return 1;
                        })))));

            // /stop dummy <name> (vanilla /stop-er sathe merge hoy; eta na challe /dummy stop <name> use kor)
            dispatcher.register(literal("stop").then(literal("dummy")
                .then(argument("name", word()).executes(c -> {
                    DummyManager.stopRecord(c.getSource().getServer(), n(c), c.getSource().getPlayer());
                    return 1;
                }))));
        });
    }
                }

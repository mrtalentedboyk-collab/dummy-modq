package com.example;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class DummyMenu {

    static ItemStack icon(Item item, String name) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return s;
    }

    static class H extends GenericContainerScreenHandler {
        final IntConsumer onClick;
        final int size;
        H(ScreenHandlerType<?> t, int id, PlayerInventory pi, Inventory inv, int rows, IntConsumer c) {
            super(t, id, pi, inv, rows);
            this.onClick = c;
            this.size = rows * 9;
        }
        @Override
        public void onSlotClick(int slot, int button, SlotActionType type, PlayerEntity player) {
            if (slot >= 0 && slot < size) onClick.accept(slot);
        }
        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
    }

    static void open(ServerPlayerEntity p, String title, ScreenHandlerType<?> type, int rows,
                     Inventory inv, IntConsumer click) {
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (id, pi, pl) -> new H(type, id, pi, inv, rows, click), Text.literal(title)));
    }

    // Right Shift menu: shob dummy-r list
    public static void openList(ServerPlayerEntity p) {
        List<String> names = new ArrayList<>(DummyManager.allNames(p.getServer()));
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < names.size() && i < 54; i++)
            inv.setStack(i, icon(Items.PLAYER_HEAD, "§a" + names.get(i)));
        if (names.isEmpty()) inv.setStack(22, icon(Items.BARRIER, "§cKono dummy nai: /dummy spawn <name>"));
        open(p, "Dummies (" + names.size() + ")", ScreenHandlerType.GENERIC_9X6, 6, inv, slot -> {
            if (slot < names.size()) openDummy(p, names.get(slot));
        });
    }

    // Dummy-r nijer menu (Shift + Right Click)
    public static void openDummy(ServerPlayerEntity p, String name) {
        MinecraftServer server = p.getServer();
        DummyPlayer cur = DummyManager.getDummy(server, name);
        boolean rec = DummyManager.isRecording(name);
        boolean botOn = cur != null && cur.bot;

        SimpleInventory inv = new SimpleInventory(27);
        inv.setStack(10, rec ? icon(Items.LIME_DYE, "§aRecord Stop & Save") : icon(Items.RED_DYE, "§cRecord Start"));
        inv.setStack(11, icon(Items.CLOCK, "§ePreview Start (replay)"));
        inv.setStack(12, icon(Items.BARRIER, "§6Preview Stop"));
        inv.setStack(13, icon(Items.IRON_SWORD, botOn ? "§aBot mode: ON (click = OFF)" : "§7Bot mode: OFF (click = ON)"));
        inv.setStack(14, icon(Items.ENDER_EYE, "§bDummy FPP view"));
        inv.setStack(15, icon(Items.SPYGLASS, "§bOwn preview"));
        inv.setStack(16, icon(Items.TNT, "§4Remove dummy"));
        inv.setStack(19, icon(Items.DIAMOND_CHESTPLATE, "Armor: Diamond"));
        inv.setStack(20, icon(Items.NETHERITE_CHESTPLATE, "Armor: Netherite"));
        inv.setStack(21, icon(Items.IRON_CHESTPLATE, "Armor: Iron"));
        inv.setStack(22, icon(Items.GLASS, "Armor: None"));

        open(p, "Dummy: " + name, ScreenHandlerType.GENERIC_9X3, 3, inv, slot -> {
            DummyPlayer d = DummyManager.getDummy(server, name);
            switch (slot) {
                case 10 -> {
                    p.closeHandledScreen();
                    if (DummyManager.isRecording(name)) DummyManager.stopRecord(server, name, p);
                    else DummyManager.startRecord(p, name);
                }
                case 11 -> { p.closeHandledScreen(); DummyManager.startReplay(server, name, 0, p); }
                case 12 -> { p.closeHandledScreen(); DummyManager.stopReplay(name); }
                case 13 -> { p.closeHandledScreen(); DummyManager.toggleBot(server, name, p); }
                case 14 -> { p.closeHandledScreen(); DummyManager.fpp(p, name); }
                case 15 -> { p.closeHandledScreen(); DummyManager.ownPreview(p, name); }
                case 16 -> { p.closeHandledScreen(); DummyManager.remove(server, name); }
                case 19 -> { if (d != null) DummyManager.armor(d, "diamond"); }
                case 20 -> { if (d != null) DummyManager.armor(d, "netherite"); }
                case 21 -> { if (d != null) DummyManager.armor(d, "iron"); }
                case 22 -> { if (d != null) DummyManager.armor(d, "none"); }
                default -> { }
            }
        });
    }
}

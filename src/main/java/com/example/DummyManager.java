package com.example;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DummyManager {
    static final Gson GSON = new Gson();

    public static class Ev { public String t; public int x, y, z; public String item; }
    public static class Frame {
        public double x, y, z; public float yaw, pitch;
        public boolean sn, sp, sw; public String item; public List<Ev> ev;
    }
    public static class Rec { public String[] armor = new String[4]; public List<Frame> frames = new ArrayList<>(); }

    static class RecState { UUID player; Rec rec = new Rec(); List<Ev> ev = new ArrayList<>(); boolean swing; }
    static class PlayState { Rec rec; int idx; PlayState(Rec r) { rec = r; } }
    static class Later { int ticks; Runnable r; Later(int t, Runnable r) { ticks = t; this.r = r; } }
    static class Preview { GameMode mode; double x, y, z; float yaw, pitch; boolean fpp; }

    static final Map<String, RecState> recording = new HashMap<>();
    static final Map<String, PlayState> playing = new HashMap<>();
    static final List<Later> later = new ArrayList<>();
    static final Map<UUID, Preview> previews = new HashMap<>();

    static String key(String n) { return n.toLowerCase(); }
    static MinecraftServer server(ServerPlayerEntity p) { return ((ServerWorld) p.getEntityWorld()).getServer(); }
    static void msg(ServerPlayerEntity p, String s) { if (p != null) p.sendMessage(Text.literal(s), false); }
    public static void later(int ticks, Runnable r) { later.add(new Later(ticks, r)); }

    public static boolean isBeingRecordedBy(ServerPlayerEntity p) { return byPlayer(p) != null; }

    // ---------- files ----------
    static File dir() {
        File d = new File(FabricLoader.getInstance().getConfigDir().toFile(), "dummymod");
        d.mkdirs();
        return d;
    }
    static void save(String name, Rec rec) {
        try { Files.writeString(new File(dir(), key(name) + ".json").toPath(), GSON.toJson(rec)); }
        catch (Exception e) { e.printStackTrace(); }
    }
    static Rec load(String name) {
        try {
            File f = new File(dir(), key(name) + ".json");
            if (!f.exists()) return null;
            return GSON.fromJson(Files.readString(f.toPath()), Rec.class);
        } catch (Exception e) { e.printStackTrace(); return null; }
    }
    public static Set<String> allNames(MinecraftServer server) {
        Set<String> s = new TreeSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
            if (p instanceof DummyPlayer) s.add(p.getName().getString());
        File[] fs = dir().listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) s.add(f.getName().replace(".json", ""));
        return s;
    }

    // ---------- helpers ----------
    public static DummyPlayer getDummy(MinecraftServer server, String name) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
        return p instanceof DummyPlayer d ? d : null;
    }
    static void ensure(MinecraftServer server, Vec3d pos, float yaw, String name, Consumer<DummyPlayer> cb) {
        DummyPlayer d = getDummy(server, name);
        if (d != null) { cb.accept(d); return; }
        DummyPlayer.spawn(server, server.getOverworld(), pos, yaw, name, cb);
    }
    public static int parseTicks(String s) {
        Matcher m = Pattern.compile("(\\d+)([smh])").matcher(s.toLowerCase());
        int sec = 0; boolean any = false;
        while (m.find()) {
            any = true;
            int n = Integer.parseInt(m.group(1));
            switch (m.group(2)) { case "h" -> sec += n * 3600; case "m" -> sec += n * 60; default -> sec += n; }
        }
        return any ? sec * 20 : -1;
    }
    public static void remove(MinecraftServer server, String name) {
        recording.remove(key(name)); playing.remove(key(name));
        DummyPlayer d = getDummy(server, name);
        if (d != null) d.removeDummy();
    }
    public static void onDummyDeath(DummyPlayer d) {
        String k = key(d.getName().getString());
        playing.remove(k);
        recording.remove(k);
    }
    public static void armor(DummyPlayer d, String type) {
        Item[] a = switch (type.toLowerCase()) {
            case "diamond" -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
            case "netherite" -> new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
            case "iron" -> new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
            default -> new Item[]{Items.AIR, Items.AIR, Items.AIR, Items.AIR};
        };
        d.equipStack(EquipmentSlot.HEAD, new ItemStack(a[0]));
        d.equipStack(EquipmentSlot.CHEST, new ItemStack(a[1]));
        d.equipStack(EquipmentSlot.LEGS, new ItemStack(a[2]));
        d.equipStack(EquipmentSlot.FEET, new ItemStack(a[3]));
    }
    public static void copyKit(ServerPlayerEntity p, String name) {
        DummyPlayer d = getDummy(server(p), name);
        if (d == null) { msg(p, "Dummy nai"); return; }
        int n = Math.min(p.getInventory().size(), d.getInventory().size());
        for (int i = 0; i < n; i++) d.getInventory().setStack(i, p.getInventory().getStack(i).copy());
        msg(p, "§aKit dummy-te copy hoyeche");
    }

    // ---------- recording ----------
    public static boolean isRecording(String name) { return recording.containsKey(key(name)); }

    public static void startRecord(ServerPlayerEntity player, String name) {
        MinecraftServer server = server(player);
        String k = key(name);
        if (recording.containsKey(k)) { msg(player, "Ageei record hocche"); return; }
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        ensure(server, pos, player.getYaw(), name, d -> {
            RecState s = new RecState();
            s.player = player.getUuid();
            playing.remove(k);
            d.setBot(false);
            d.mirroring = true;
            d.setInvulnerable(true);
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY,
                StatusEffectInstance.INFINITE, 0, false, false, false));
            recording.put(k, s);
            msg(player, "§cRecording shuru: " + name + " (stop korte /stop dummy " + name + ")");
        });
    }

    public static boolean stopRecord(MinecraftServer server, String name, ServerPlayerEntity caller) {
        String k = key(name);
        RecState s = recording.remove(k);
        if (s == null) return false;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.player);
        if (p != null) {
            p.removeStatusEffect(StatusEffects.INVISIBILITY);
            msg(p, "§aRecording save hoyeche: " + name + " (" + s.rec.frames.size() / 20 + " second). Dekhte: preview start");
        }
        DummyPlayer d = getDummy(server, name);
        if (d != null) {
            d.mirroring = false;
            d.setInvulnerable(false);
            later(1, d::removeDummy);
        }
        if (!s.rec.frames.isEmpty()) save(name, s.rec);
        return true;
    }

    static RecState byPlayer(ServerPlayerEntity p) {
        for (RecState s : recording.values()) if (s.player.equals(p.getUuid())) return s;
        return null;
    }
    public static void onBreak(ServerPlayerEntity p, BlockPos pos) {
        RecState s = byPlayer(p); if (s == null) return;
        Ev e = new Ev(); e.t = "b"; e.x = pos.getX(); e.y = pos.getY(); e.z = pos.getZ();
        s.ev.add(e); s.swing = true;
    }
    public static void onUse(ServerPlayerEntity p, BlockHitResult hit) {
        RecState s = byPlayer(p); if (s == null) return;
        s.swing = true;
        if (p.getMainHandStack().getItem() instanceof BlockItem) {
            BlockPos pos = hit.getBlockPos().offset(hit.getSide());
            Ev e = new Ev(); e.t = "p"; e.x = pos.getX(); e.y = pos.getY(); e.z = pos.getZ();
            e.item = Registries.ITEM.getId(p.getMainHandStack().getItem()).toString();
            s.ev.add(e);
        }
    }
    public static void onSwing(ServerPlayerEntity p) {
        RecState s = byPlayer(p); if (s != null) s.swing = true;
    }

    // ---------- replay ----------
    public static void startReplay(MinecraftServer server, String name, int delayTicks, ServerPlayerEntity caller) {
        String k = key(name);
        if (recording.containsKey(k)) { msg(caller, "Record cholar somoy preview hobe na"); return; }
        Rec rec = load(name);
        if (rec == null || rec.frames.isEmpty()) { msg(caller, "Ei nam-er kono recording nai: " + name); return; }
        Runnable go = () -> {
            Frame f0 = rec.frames.get(0);
            ensure(server, new Vec3d(f0.x, f0.y, f0.z), f0.yaw, name, d -> {
                d.setBot(false);
                d.mirroring = false;
                d.setInvulnerable(false);
                playing.put(k, new PlayState(rec));
                msg(caller, "§aPreview shuru: " + name);
            });
        };
        if (delayTicks <= 0) go.run();
        else {
            later(delayTicks, go);
            msg(caller, "Preview " + (delayTicks / 20) + " second pore shuru hobe");
        }
    }
    public static void stopReplay(String name) { playing.remove(key(name)); }

    static void apply(DummyPlayer d, Frame f) {
        d.refreshPositionAndAngles(f.x, f.y, f.z, f.yaw, f.pitch);
        d.setHeadYaw(f.yaw);
        d.setBodyYaw(f.yaw);
        d.setSneaking(f.sn);
        d.setSprinting(f.sp);
        d.fallDistance = 0;
        if (f.item != null)
            d.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Registries.ITEM.get(Identifier.of(f.item))));
        if (f.sw) d.swingHand(Hand.MAIN_HAND, true);
    }
    static void doEvent(DummyPlayer d, Ev e) {
        ServerWorld w = (ServerWorld) d.getEntityWorld();
        BlockPos pos = new BlockPos(e.x, e.y, e.z);
        if ("b".equals(e.t)) w.breakBlock(pos, true, d);
        else if ("p".equals(e.t)) {
            Item it = Registries.ITEM.get(Identifier.of(e.item));
            if (it instanceof BlockItem bi) w.setBlockState(pos, bi.getBlock().getDefaultState());
        }
    }

    // ---------- bot ----------
    public static void toggleBot(MinecraftServer server, String name, ServerPlayerEntity caller) {
        DummyPlayer d = getDummy(server, name);
        if (d == null) { msg(caller, "Dummy nai, age /dummy spawn " + name); return; }
        playing.remove(key(name));
        d.setBot(!d.bot);
        msg(caller, "Bot mode " + (d.bot ? "ON" : "OFF") + ": " + name);
    }

    // ---------- previews ----------
    static void enterPreview(ServerPlayerEntity p, boolean fpp) {
        Preview pv = previews.get(p.getUuid());
        if (pv == null) {
            pv = new Preview();
            pv.mode = p.interactionManager.getGameMode();
            pv.x = p.getX(); pv.y = p.getY(); pv.z = p.getZ();
            pv.yaw = p.getYaw(); pv.pitch = p.getPitch();
            previews.put(p.getUuid(), pv);
        }
        pv.fpp = fpp;
        p.changeGameMode(GameMode.SPECTATOR);
    }
    public static void fpp(ServerPlayerEntity p, String name) {
        DummyPlayer d = getDummy(server(p), name);
        if (d == null) { msg(p, "Dummy nai (age preview start ba spawn koro)"); return; }
        if (isRecording(name)) { msg(p, "Record cholar somoy FPP hobe na"); return; }
        enterPreview(p, true);
        p.setCameraEntity(d);
        msg(p, "FPP view: Shift chap bar korar jonno");
    }
    public static void ownPreview(ServerPlayerEntity p, String name) {
        DummyPlayer d = getDummy(server(p), name);
        if (d == null) { msg(p, "Dummy nai (age preview start ba spawn koro)"); return; }
        if (isRecording(name)) { msg(p, "Record cholar somoy hobe na"); return; }
        enterPreview(p, false);
        p.networkHandler.requestTeleport(d.getX() + 3, d.getY() + 2, d.getZ() + 3, 135f, 30f);
        msg(p, "Own preview: ber hote /dummy exit");
    }
    public static boolean exitPreview(ServerPlayerEntity p) {
        Preview pv = previews.remove(p.getUuid());
        if (pv == null) return false;
        p.setCameraEntity(p);
        p.changeGameMode(pv.mode);
        p.networkHandler.requestTeleport(pv.x, pv.y, pv.z, pv.yaw, pv.pitch);
        return true;
    }

    // ---------- main tick ----------
    public static void tick(MinecraftServer server) {
        List<Runnable> due = new ArrayList<>();
        later.removeIf(l -> { if (--l.ticks <= 0) { due.add(l.r); return true; } return false; });
        due.forEach(Runnable::run);

        for (String k : new ArrayList<>(recording.keySet())) {
            RecState s = recording.get(k);
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.player);
            DummyPlayer d = getDummy(server, k);
            if (p == null || d == null) { stopRecord(server, k, p); continue; }
            Frame f = new Frame();
            f.x = p.getX(); f.y = p.getY(); f.z = p.getZ();
            f.yaw = p.getYaw(); f.pitch = p.getPitch();
            f.sn = p.isSneaking(); f.sp = p.isSprinting();
            f.item = Registries.ITEM.getId(p.getMainHandStack().getItem()).toString();
            f.sw = s.swing; s.swing = false;
            if (!s.ev.isEmpty()) { f.ev = new ArrayList<>(s.ev); s.ev.clear(); }
            s.rec.frames.add(f);
            apply(d, f);
            // dummy-ke recording-er player-er client theke lukiye rakhi (first person-e shudhu nijeke dekhbi)
            p.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(d.getId()));
        }

        for (String k : new ArrayList<>(playing.keySet())) {
            PlayState ps = playing.get(k);
            DummyPlayer d = getDummy(server, k);
            if (d == null || ps.idx >= ps.rec.frames.size()) { playing.remove(k); continue; }
            Frame f = ps.rec.frames.get(ps.idx++);
            apply(d, f);
            if (f.ev != null) for (Ev e : f.ev) doEvent(d, e);
        }

        for (UUID id : new ArrayList<>(previews.keySet())) {
            Preview pv = previews.get(id);
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) { previews.remove(id); continue; }
            if (pv.fpp && p.getCameraEntity() == p) exitPreview(p);
        }
    }
}

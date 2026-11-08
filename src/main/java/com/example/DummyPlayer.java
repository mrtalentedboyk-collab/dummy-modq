package com.example;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

public class DummyPlayer extends ServerPlayerEntity {

    // ----- bot settings (menu theke bodlay) -----
    public boolean bot = false;
    public int level = 2; // 1 Easy, 2 Normal, 3 Hard, 4 Extreme, 5 Hacker
    public boolean optStrafe = true, optCrit = true, optBridge = true, optHeal = true, optCrystal = false;
    public boolean mirroring = false;
    public FakeConnection fake;

    public static final String[] LV = {"Easy", "Normal", "Hard", "Extreme", "Hacker"};
    static final int[] REACT = {25, 12, 6, 2, 0};
    static final double[] SPEED = {0.5, 0.8, 1.0, 1.0, 1.0};
    static final double[] REACH = {2.5, 2.9, 3.1, 3.3, 4.6};
    static final double[] MISS = {0.45, 0.25, 0.10, 0.03, 0.0};
    static final float[] JITTER = {18f, 8f, 3f, 1f, 0f};
    static final int[] ATK_DELAY = {30, 16, 10, 8, 5};

    private final Random rng = new Random();
    private int seen, lastAtk, healCd, crystalCd, gearCd, ticks, strafeDir = 1;
    private BlockPos towerBase;
    private boolean gliding, elytraOn;
    private ItemStack savedChest = ItemStack.EMPTY;
    private EndCrystalEntity lastCrystal;

    public DummyPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(server, world, profile, SyncedClientOptions.createDefault());
    }

    private MinecraftServer srv() {
        return ((ServerWorld) this.getEntityWorld()).getServer();
    }

    @Override
    public boolean isPushable() {
        return !mirroring && super.isPushable();
    }

    // ---------------- spawn ----------------
    public static void spawn(MinecraftServer server, ServerWorld world, Vec3d pos, float yaw,
                             String name, Consumer<DummyPlayer> after) {
        if (server.getPlayerManager().getPlayer(name) != null) {
            if (after != null && server.getPlayerManager().getPlayer(name) instanceof DummyPlayer d) after.accept(d);
            else server.getPlayerManager().broadcast(Text.literal("Ei nam-er player ageei ache: " + name), false);
            return;
        }
        server.getPlayerManager().broadcast(Text.literal("Dummy spawn hocche: " + name), false);
        new Thread(() -> {
            GameProfile profile = fetchProfile(name);
            server.execute(() -> {
                try {
                    DummyPlayer p = new DummyPlayer(server, world, profile);
                    FakeConnection fc = new FakeConnection();
                    p.fake = fc;
                    p.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0);
                    server.getPlayerManager().onPlayerConnect(
                        fc, p,
                        new ConnectedClientData(profile, 0, p.getClientOptions(), false));
                    p.setHealth(p.getMaxHealth());
                    p.getHungerManager().setFoodLevel(20);
                    p.networkHandler.requestTeleport(pos.x, pos.y, pos.z, yaw, 0);
                    if (after != null) after.accept(p);
                } catch (Throwable t) {
                    t.printStackTrace();
                    server.getPlayerManager().broadcast(Text.literal("§cDummy error: " + t), false);
                }
            });
        }).start();
    }

    private static GameProfile fetchProfile(String name) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            String r1 = http.send(HttpRequest.newBuilder(
                URI.create("https://api.mojang.com/users/profiles/minecraft/" + name)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
            JsonObject o1 = JsonParser.parseString(r1).getAsJsonObject();
            String id = o1.get("id").getAsString();
            String realName = o1.get("name").getAsString();
            String r2 = http.send(HttpRequest.newBuilder(
                URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
            JsonArray props = JsonParser.parseString(r2).getAsJsonObject().getAsJsonArray("properties");
            Multimap<String, Property> map = LinkedHashMultimap.create();
            for (JsonElement e : props) {
                JsonObject p = e.getAsJsonObject();
                map.put(p.get("name").getAsString(), new Property(
                    p.get("name").getAsString(), p.get("value").getAsString(),
                    p.has("signature") ? p.get("signature").getAsString() : null));
            }
            UUID uuid = UUID.fromString(id.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
            return new GameProfile(uuid, realName, new PropertyMap(map));
        } catch (Exception ex) {
            return new GameProfile(UUID.nameUUIDFromBytes(("Dummy:" + name).getBytes()), name);
        }
    }

    public void removeDummy() {
        this.networkHandler.onDisconnected(new DisconnectionInfo(Text.literal("Dummy removed")));
    }

    // ---------------- tick ----------------
    @Override
    public void tick() {
        if (this.isDead()) {
            DummyManager.onDummyDeath(this);
            DummyManager.later(2, this::removeDummy);
            return;
        }
        if (srv().getTicks() % 10 == 0) {
            this.networkHandler.syncWithPlayerPosition();
            ((ServerWorld) this.getEntityWorld()).getChunkManager().updatePosition(this);
        }
        if (fake != null && srv().getTicks() % 40 == 0) fake.clean();
        if (bot) botTick();
        super.tick();
        this.playerTick();
    }

    public void setBot(boolean on) {
        this.bot = on;
        if (!on) {
            this.forwardSpeed = 0;
            this.sidewaysSpeed = 0;
            setSprinting(false);
            endGlide();
        } else if (getMainHandStack().isEmpty()) {
            equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        }
    }

    // ---------------- bot brain ----------------
    private void botTick() {
        ticks++;
        ServerWorld w = (ServerWorld) this.getEntityWorld();
        int lv = Math.max(0, Math.min(4, level - 1));

        if (optHeal && level >= 2) autoHeal();
        if (gearCd-- <= 0) { autoArmor(); gearCd = 100; }

        ServerPlayerEntity target = findTarget();
        if (target == null) {
            this.forwardSpeed = 0;
            this.sidewaysSpeed = 0;
            seen = 0;
            endGlide();
            return;
        }
        seen++;
        aim(target, JITTER[lv]);
        if (seen < REACT[lv]) { this.forwardSpeed = 0; this.sidewaysSpeed = 0; return; }

        double d3 = this.distanceTo(target);
        double dx = target.getX() - getX(), dz = target.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double dy = target.getY() - getY();

        autoWeapon();
        if (level >= 4 && elytraChase(target, d3)) return;
        if (optCrystal && level >= 4 && crystalTick(w, target, d3)) return;

        boolean near = d3 <= REACH[lv] + 0.3;
        this.forwardSpeed = near ? 0f : (float) SPEED[lv];
        setSprinting(!near);
        if (optStrafe && level >= 2 && d3 < 6) {
            if (ticks % 25 == 0) strafeDir = rng.nextBoolean() ? 1 : -1;
            this.sidewaysSpeed = strafeDir * 0.7f;
        } else {
            this.sidewaysSpeed = 0;
        }
        if (this.horizontalCollision && this.isOnGround()) this.jump();

        if (optBridge && level >= 3) buildTick(w, dy, dist);

        // attack
        if (d3 <= REACH[lv] && getAttackCooldownProgress(0.5f) >= 0.95f && ticks - lastAtk >= ATK_DELAY[lv]) {
            boolean critMode = optCrit && level >= 3;
            if (critMode && isOnGround() && ticks - lastAtk < 40) {
                this.jump();
            } else if (critMode && !isOnGround() && getVelocity().y >= -0.05) {
                // niche porar jonno opekkha (crit / mace smash)
            } else {
                lastAtk = ticks;
                swingHand(Hand.MAIN_HAND);
                if (rng.nextDouble() >= MISS[lv]) attack(target);
            }
        }
    }

    private ServerPlayerEntity findTarget() {
        ServerPlayerEntity target = null;
        double best = 40 * 40;
        for (ServerPlayerEntity pl : srv().getPlayerManager().getPlayerList()) {
            if (pl == this || pl instanceof DummyPlayer || pl.isSpectator() || pl.isCreative()) continue;
            if (DummyManager.isBeingRecordedBy(pl)) continue;
            double d = this.squaredDistanceTo(pl);
            if (d < best) { best = d; target = pl; }
        }
        return target;
    }

    private void aim(ServerPlayerEntity t, float jitter) {
        double dx = t.getX() - getX(), dz = t.getZ() - getZ();
        double dy = t.getEyeY() - getEyeY();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f + (rng.nextFloat() - 0.5f) * 2f * jitter;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))
            + (rng.nextFloat() - 0.5f) * 2f * jitter;
        setYaw(yaw); setPitch(pitch); setHeadYaw(yaw); setBodyYaw(yaw);
    }

    // ---------------- gear ----------------
    private static int rankOf(String id) {
        if (id.contains("netherite")) return 5;
        if (id.contains("diamond")) return 4;
        if (id.contains("iron")) return 3;
        if (id.contains("stone") || id.contains("chainmail")) return 2;
        if (id.contains("golden")) return 1;
        return 0;
    }

    private int findSlot(Item item) {
        for (int i = 0; i < 36; i++) if (getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }

    private void autoWeapon() {
        int best = -1, bs = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            int sc = -1;
            if (id.equals("mace")) sc = level >= 3 ? 60 : -1;
            else if (id.endsWith("_sword")) sc = 20 + rankOf(id);
            else if (id.endsWith("_axe")) sc = 15 + rankOf(id);
            if (sc > bs) { bs = sc; best = i; }
        }
        if (best >= 0) {
            ItemStack b = getInventory().getStack(best);
            if (getMainHandStack().getItem() != b.getItem())
                equipStack(EquipmentSlot.MAINHAND, b.copy());
        }
    }

    private void autoArmor() {
        EquipmentSlot[] sl = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        String[] suf = {"_helmet", "_chestplate", "_leggings", "_boots"};
        for (int k = 0; k < 4; k++) {
            if (elytraOn && k == 1) continue;
            ItemStack cur = getEquippedStack(sl[k]);
            String cid = Registries.ITEM.getId(cur.getItem()).getPath();
            int bestRank = (!cur.isEmpty() && cid.endsWith(suf[k])) ? rankOf(cid) : -1;
            ItemStack best = null;
            for (int i = 0; i < 36; i++) {
                ItemStack s = getInventory().getStack(i);
                if (s.isEmpty()) continue;
                String id = Registries.ITEM.getId(s.getItem()).getPath();
                if (id.endsWith(suf[k]) && rankOf(id) > bestRank) { bestRank = rankOf(id); best = s; }
            }
            if (best != null) equipStack(sl[k], best.copy());
        }
    }

    private void autoHeal() {
        if (getOffHandStack().isEmpty()) {
            int t = findSlot(Items.TOTEM_OF_UNDYING);
            if (t >= 0) {
                getInventory().getStack(t).decrement(1);
                equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            }
        }
        if (healCd > 0) healCd--;
        if (healCd == 0 && getHealth() < 10f) {
            int g = findSlot(Items.ENCHANTED_GOLDEN_APPLE);
            if (g < 0) g = findSlot(Items.GOLDEN_APPLE);
            if (g >= 0) {
                getInventory().getStack(g).decrement(1);
                setHealth(Math.min(getMaxHealth(), getHealth() + 6f));
                addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 2400, 0));
                addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 1));
                swingHand(Hand.MAIN_HAND);
                healCd = 60;
            }
        }
    }

    // ---------------- block bridge / tower ----------------
    private static boolean isBuildable(String id) {
        return id.contains("planks") || id.contains("cobble") || id.equals("stone") || id.contains("dirt")
            || id.contains("wool") || id.contains("deepslate") || id.contains("concrete")
            || id.contains("terracotta") || id.equals("netherrack") || id.equals("end_stone")
            || id.contains("stone_bricks") || id.contains("sandstone") || id.equals("obsidian");
    }

    private boolean placeBlock(ServerWorld w, BlockPos pos) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = getInventory().getStack(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            if (!isBuildable(Registries.ITEM.getId(s.getItem()).getPath())) continue;
            w.setBlockState(pos, bi.getBlock().getDefaultState());
            s.decrement(1);
            swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void buildTick(ServerWorld w, double dy, double dist) {
        // tower: target upore thakle
        if (dy > 1.6 && dist < 6) {
            if (isOnGround()) {
                towerBase = getBlockPos();
                this.jump();
            } else if (towerBase != null && getY() >= towerBase.getY() + 1.0 && w.getBlockState(towerBase).isAir()) {
                placeBlock(w, towerBase);
                towerBase = null;
            }
            return;
        }
        // bridge: samne gap thakle block bosay
        if (isOnGround() && dist > 2) {
            Vec3d dir = Vec3d.fromPolar(0f, getYaw());
            BlockPos ahead = BlockPos.ofFloored(getX() + dir.x, getY() - 1.0, getZ() + dir.z);
            if (w.getBlockState(ahead).isAir()) placeBlock(w, ahead);
        }
    }

    // ---------------- crystal ----------------
    private boolean crystalTick(ServerWorld w, ServerPlayerEntity target, double d3) {
        int cs = findSlot(Items.END_CRYSTAL);
        if (cs < 0 && (lastCrystal == null || !lastCrystal.isAlive())) return false;

        if (lastCrystal != null && lastCrystal.isAlive()) {
            swingHand(Hand.MAIN_HAND);
            attack(lastCrystal);
            lastCrystal = null;
            crystalCd = level >= 5 ? 4 : 10;
            return true;
        }
        if (d3 < 5.5) this.forwardSpeed = -0.7f;
        else if (d3 > 9) this.forwardSpeed = 1f;
        else this.forwardSpeed = 0;
        this.sidewaysSpeed = 0;

        if (crystalCd > 0) { crystalCd--; return true; }
        if (d3 >= 5 && d3 <= 10) {
            getInventory().getStack(cs).decrement(1);
            EndCrystalEntity c = new EndCrystalEntity(w, target.getX(), target.getY(), target.getZ());
            c.setShowBottom(false);
            w.spawnEntity(c);
            lastCrystal = c;
            crystalCd = 6;
        }
        return true;
    }

    // ---------------- elytra ----------------
    private boolean elytraChase(ServerPlayerEntity target, double d3) {
        if (!elytraOn) {
            if (d3 < 22 || findSlot(Items.ELYTRA) < 0) return false;
            savedChest = getEquippedStack(EquipmentSlot.CHEST).copy();
            equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            elytraOn = true;
            if (isOnGround()) this.jump();
            return true;
        }
        if (!isOnGround() && !gliding && getVelocity().y < 0) {
            setFlag(7, true);
            gliding = true;
        }
        if (gliding || !isOnGround()) {
            if (d3 < 8) { endGlide(); return false; }
            Vec3d dir = new Vec3d(target.getX() - getX(), target.getEyeY() - getY() + d3 * 0.15,
                target.getZ() - getZ()).normalize().multiply(1.1);
            setVelocity(dir);
            return true;
        }
        endGlide();
        return false;
    }

    private void endGlide() {
        if (!elytraOn) return;
        setFlag(7, false);
        gliding = false;
        elytraOn = false;
        this.fallDistance = 0;
        equipStack(EquipmentSlot.CHEST, savedChest);
        savedChest = ItemStack.EMPTY;
    }

    // ---------------- fake connection ----------------
    public static class FakeConnection extends ClientConnection {
        private final EmbeddedChannel ch = new EmbeddedChannel();

        public FakeConnection() {
            super(NetworkSide.SERVERBOUND);
            try {
                for (Field f : ClientConnection.class.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType() == Channel.class) {
                        f.setAccessible(true);
                        f.set(this, ch);
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        public void clean() {
            try { ch.releaseOutbound(); } catch (Throwable ignored) { }
        }
    }
                    }

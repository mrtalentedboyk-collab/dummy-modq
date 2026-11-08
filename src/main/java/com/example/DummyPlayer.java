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
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.function.Consumer;

public class DummyPlayer extends ServerPlayerEntity {

    public boolean bot = false;

    public DummyPlayer(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(server, world, profile, SyncedClientOptions.createDefault());
    }

    private MinecraftServer srv() {
        return ((ServerWorld) this.getEntityWorld()).getServer();
    }

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
                    p.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0);
                    server.getPlayerManager().onPlayerConnect(
                        new FakeConnection(), p,
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
        if (bot) botTick();
        super.tick();
        this.playerTick();
    }

    private void botTick() {
        ServerPlayerEntity target = null;
        double best = 30 * 30;
        for (ServerPlayerEntity pl : srv().getPlayerManager().getPlayerList()) {
            if (pl == this || pl instanceof DummyPlayer || pl.isSpectator() || pl.isCreative()) continue;
            double d = this.squaredDistanceTo(pl);
            if (d < best) { best = d; target = pl; }
        }
        if (target == null) { this.forwardSpeed = 0; return; }

        double dx = target.getX() - getX(), dz = target.getZ() - getZ();
        double dy = target.getEyeY() - getEyeY();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        setYaw(yaw); setPitch(pitch); setHeadYaw(yaw); setBodyYaw(yaw);

        if (best > 2.8 * 2.8) {
            this.forwardSpeed = 1.0f;
            setSprinting(true);
            if (this.horizontalCollision && this.isOnGround()) this.jump();
        } else {
            this.forwardSpeed = 0;
            setSprinting(false);
            if (getAttackCooldownProgress(0.5f) >= 1f) {
                swingHand(Hand.MAIN_HAND);
                attack(target);
            }
        }
    }

    public void setBot(boolean on) {
        this.bot = on;
        if (!on) { this.forwardSpeed = 0; setSprinting(false); }
        else if (getMainHandStack().isEmpty()) equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
    }

    public static class FakeConnection extends ClientConnection {
        public FakeConnection() { super(NetworkSide.SERVERBOUND); }
    }
}

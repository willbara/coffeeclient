package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.module.modules.render.Freecam;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Players;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.Holder;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Custom world-projected labels for selected entities, players, and dropped items. */
public final class Nametags extends Module {
    public enum Durability { NONE, TOTAL, PERCENTAGE }
    public enum EnchantmentPosition { ABOVE, ON_TOP }
    public enum DistanceColorMode { GRADIENT, FLAT }

    public final EntityTypeListSetting entities = group(new EntityTypeListSetting("Entities", "Entity types to label",
        List.of("minecraft:player", "minecraft:item")), "General");
    public final NumberSetting scale = group(new NumberSetting("Scale", "Nametag size", 1.1, 0.1, 3.0, 0.05), "General");
    public final BooleanSetting ignoreSelf = group(new BooleanSetting("Ignore self", "Hide your own nametag", true), "General");
    public final BooleanSetting ignoreFriends = group(new BooleanSetting("Ignore friends", "Hide nametags for friends", false), "General");
    public final BooleanSetting ignoreBots = group(new BooleanSetting("Ignore bots", "Hide players missing from the tab list", true), "General");
    public final BooleanSetting culling = group(new BooleanSetting("Culling", "Limit labels by distance and count", false), "General");
    public final NumberSetting cullingRange = group(new NumberSetting("Culling range", "Maximum camera distance for labels", 20, 0, 200, 1), "General");
    public final NumberSetting cullingCount = group(new NumberSetting("Culling count", "Maximum number of labels drawn", 50, 1, 100, 1), "General");

    public final BooleanSetting displayHealth = group(new BooleanSetting("Health", "Show living entity health", true), "Players");
    public final BooleanSetting displayPrefix = group(new BooleanSetting("Use display name", "Use the server display name instead of the account name", false), "Players");
    public final BooleanSetting displayGameMode = group(new BooleanSetting("Game mode", "Show player game mode", false), "Players");
    public final BooleanSetting displayDistance = group(new BooleanSetting("Distance", "Show distance to the entity", false), "Players");
    public final BooleanSetting displayPing = group(new BooleanSetting("Ping", "Show player ping", true), "Players");
    public final BooleanSetting displayItems = group(new BooleanSetting("Equipment", "Show held items and armor above player labels", true), "Players");
    public final NumberSetting itemSpacing = group(new NumberSetting("Item spacing", "Gap between equipment icons", 2, 0, 10, 1), "Players");
    public final BooleanSetting ignoreEmptySlots = group(new BooleanSetting("Ignore empty slots", "Collapse empty equipment slots", true), "Players");
    public final EnumSetting<Durability> itemDurability = group(new EnumSetting<>("Durability", "Show remaining item durability", Durability.NONE), "Players");
    public final BooleanSetting displayEnchants = group(new BooleanSetting("Enchantments", "Show selected enchantments above equipment", false), "Players");
    public final IdListSetting shownEnchantments = group(new IdListSetting("Shown enchantments", "Enchantments to display",
        List.of("minecraft:protection", "minecraft:blast_protection", "minecraft:fire_protection", "minecraft:projectile_protection"),
        EnchantmentIds::all, false).labels(EnchantmentIds::name), "Players");
    public final EnumSetting<EnchantmentPosition> enchantmentPosition = group(new EnumSetting<>("Enchantment position", "Place enchantment text above or over equipment", EnchantmentPosition.ABOVE), "Players");
    public final NumberSetting enchantNameLength = group(new NumberSetting("Enchant name length", "Maximum characters in enchantment names", 3, 1, 5, 1), "Players");
    public final NumberSetting enchantTextScale = group(new NumberSetting("Enchant text scale", "Size of equipment enchantment text", 1, 0.1, 2, 0.05), "Players");

    public final BooleanSetting itemCount = group(new BooleanSetting("Show item count", "Show stack count on dropped-item labels", true), "Items");

    public final ColorSetting backgroundColor = group(new ColorSetting("Background color", "Nametag background", 0xC0000000), "Render");
    public final ColorSetting nameColor = group(new ColorSetting("Name color", "Default nametag text color", 0xFFFFFFFF), "Render");
    public final ColorSetting pingColor = group(new ColorSetting("Ping color", "Player ping text color", 0xFF14AAAA), "Render");
    public final ColorSetting gameModeColor = group(new ColorSetting("Game mode color", "Player game mode text color", 0xFFE8B923), "Render");
    public final EnumSetting<DistanceColorMode> distanceColorMode = group(new EnumSetting<>("Distance color mode", "Flat color or distance gradient", DistanceColorMode.GRADIENT), "Render");
    public final ColorSetting distanceColor = group(new ColorSetting("Distance color", "Distance text when using flat mode", 0xFF969696), "Render");

    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f vec = new Vector4f();
    private Vec3 cameraPos = Vec3.ZERO;
    private int screenWidth, screenHeight;
    private float partialTick;

    public Nametags() {
        super("Nametags", Category.RENDER, "Custom labels for players, entities, and dropped items.");
        for (Setting<?> setting : List.of(cullingRange, cullingCount)) setting.visibleWhen(culling::get);
        for (Setting<?> setting : List.of(displayPrefix, displayGameMode, displayDistance, displayPing, displayItems,
            itemSpacing, ignoreEmptySlots, itemDurability, displayEnchants)) setting.visibleWhen(() -> entities.contains("minecraft:player"));
        for (Setting<?> setting : List.of(shownEnchantments, enchantmentPosition, enchantNameLength, enchantTextScale)) {
            setting.visibleWhen(() -> entities.contains("minecraft:player") && displayItems.get() && displayEnchants.get());
        }
        distanceColorMode.visibleWhen(displayDistance::get);
        distanceColor.visibleWhen(() -> displayDistance.get() && distanceColorMode.get() == DistanceColorMode.FLAT);
        itemCount.visibleWhen(() -> entities.contains("minecraft:item"));
    }

    @Override
    public boolean hasConfigScreen() {
        return true;
    }

    @Override
    public IdListSetting embeddedList() {
        return entities;
    }

    @Override
    protected void init(EventBus bus) {
        bus.subscribe(Events.HudRender.class, this::onHudRender);
    }

    private record Target(Entity entity, double dist2) {
    }

    private void onHudRender(Events.HudRender event) {
        if (!isEnabled()) return;
        Minecraft mc = mc();
        if (mc.level == null || mc.player == null) return;

        partialTick = event.delta().getGameTimeDeltaPartialTick(false);
        GuiGraphicsExtractor graphics = event.graphics();

        screenWidth = graphics.guiWidth();
        screenHeight = graphics.guiHeight();

        var camera = mc.gameRenderer.mainCamera();
        if (!camera.isInitialized()) return;
        cameraPos = camera.position();
        WorldProjector.levelViewProjection(mc, viewProjection);

        boolean cull = culling.get();
        double maxDist2 = cullingRange.get() * cullingRange.get();
        List<Target> targets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isTarget(mc, entity)) continue;
            double dist2 = cameraPos.distanceToSqr(entity.getPosition(partialTick));
            if (cull && dist2 > maxDist2) continue;
            targets.add(new Target(entity, dist2));
        }
        // Nearest first so culling keeps the closest labels; drawn far to near so near ones end up on top.
        targets.sort(Comparator.comparingDouble(Target::dist2));
        int count = cull ? Math.min(targets.size(), cullingCount.intValue()) : targets.size();
        for (int i = count - 1; i >= 0; i--) drawEntity(graphics, mc, targets.get(i).entity(), partialTick);
    }

    /** Whether this module owns labels for the entity's type. */
    public boolean handles(Entity entity) {
        return entities.has(entity);
    }

    public boolean isTarget(Entity entity) {
        Minecraft mc = mc();
        return isTarget(mc, entity);
    }

    private boolean isTarget(Minecraft mc, Entity entity) {
        if (!handles(entity)) return false;
        if (ignoreSelf.get() && entity == mc.player) return false;
        if (entity.isInvisible()) return false;
        if (entity instanceof Player player) {
            String name = player.getName().getString();
            if (ignoreFriends.get() && Friends.is(name, dev.coffeeclient.friend.FriendTrait.HIGHLIGHTED)) return false;
            if (ignoreBots.get() && entity != mc.player && mc.getConnection() != null
                && !Players.isListed(player.getUUID())) return false;
        }
        return true;
    }

    private void drawEntity(GuiGraphicsExtractor graphics, Minecraft mc, Entity entity, float partialTick) {
        Vec3 position = entity.getPosition(partialTick);
        double yOffset = entity instanceof ItemEntity || entity instanceof ItemFrame ? 0.3 : entity.getEyeHeight() + 0.35;
        float[] point = project(position.x, position.y + yOffset, position.z);
        if (point == null || !onScreen(point, 30)) return;

        List<Nametag.Line> lines = new ArrayList<>();
        String name = entity.getDisplayName().getString();
        int nameColorArgb = Colors.withAlpha(nameColor.argb(), 255);

        if (entity instanceof Player player) {
            if (!displayPrefix.get()) name = player.getName().getString();

            // Build name line with health as prefix
            if (displayHealth.get()) {
                float health = player.getHealth() + player.getAbsorptionAmount();
                float ratio = health / Math.max(1, player.getMaxHealth());
                int healthColor = ratio <= 0.333f ? 0xFFFF1919 : ratio <= 0.666f ? 0xFFFF6919 : 0xFF19FC19;
                String healthStr = String.format(Locale.ROOT, "%.0f ", health);
                // Render as segments: health prefix + name
                List<Nametag.Segment> segments = new ArrayList<>();
                segments.add(new Nametag.Segment(healthStr, healthColor));
                segments.add(new Nametag.Segment(name, nameColorArgb));
                if (displayGameMode.get()) {
                    PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
                    segments.add(new Nametag.Segment(" [" + gameModeName(info) + "]", gameModeColor.argb()));
                }
                if (displayPing.get()) {
                    PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
                    if (info != null) segments.add(new Nametag.Segment(" [" + info.getLatency() + "ms]", pingColor.argb()));
                }
                if (displayDistance.get() && (player != mc.player || ModuleManager.on(Freecam.class))) {
                    double distance = Math.round(cameraPos.distanceTo(position) * 10) / 10.0;
                    int distColor = distanceColorMode.get() == DistanceColorMode.FLAT ? distanceColor.argb() : Colors.lerp(0xFF19FC19, 0xFFFF1919, (float) Math.min(1, distance / 100.0));
                    segments.add(new Nametag.Segment(String.format(Locale.ROOT, " %.1fm", distance), distColor));
                }
                Nametag.drawSegments(graphics, point[0], point[1], segments, scale.floatValue(), true, backgroundColor.argb(), null);
            } else {
                // No health - just name with other info as segments
                List<Nametag.Segment> segments = new ArrayList<>();
                segments.add(new Nametag.Segment(name, nameColorArgb));
                if (displayGameMode.get()) {
                    PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
                    segments.add(new Nametag.Segment(" [" + gameModeName(info) + "]", gameModeColor.argb()));
                }
                if (displayPing.get()) {
                    PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
                    if (info != null) segments.add(new Nametag.Segment(" [" + info.getLatency() + "ms]", pingColor.argb()));
                }
                if (displayDistance.get() && (player != mc.player || ModuleManager.on(Freecam.class))) {
                    double distance = Math.round(cameraPos.distanceTo(position) * 10) / 10.0;
                    int distColor = distanceColorMode.get() == DistanceColorMode.FLAT ? distanceColor.argb() : Colors.lerp(0xFF19FC19, 0xFFFF1919, (float) Math.min(1, distance / 100.0));
                    segments.add(new Nametag.Segment(String.format(Locale.ROOT, " %.1fm", distance), distColor));
                }
                Nametag.drawSegments(graphics, point[0], point[1], segments, scale.floatValue(), true, backgroundColor.argb(), null);
            }

            // Equipment
            if (displayItems.get()) {
                List<ItemStack> equipment = equipment(player);
                List<List<Nametag.Line>> enchantLines = equipmentEnchantments(equipment);
                List<String> durability = equipmentDurability(equipment);
                boolean showDurability = itemDurability.get() != Durability.NONE;
                int labelHeight = Nametag.height(List.of(new Nametag.Line(name, nameColorArgb)), -1); // approximate
                Nametag.drawEquipment(graphics, point[0], point[1] - labelHeight * scale.floatValue() - 2 * scale.floatValue(),
                    equipment, enchantLines, durability, scale.floatValue(), itemSpacing.floatValue(), ignoreEmptySlots.get(),
                    enchantmentPosition.get() == EnchantmentPosition.ON_TOP, enchantTextScale.floatValue());
            }

        } else if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getItem();
            name = stack.getHoverName().getString();
            if (itemCount.get() && stack.getCount() > 1) name += " x" + stack.getCount();
            Nametag.draw(graphics, point[0], point[1], List.of(new Nametag.Line(name, nameColorArgb)), scale.floatValue(), true, backgroundColor.argb(), -1, 0, null);

        } else if (entity instanceof LivingEntity living) {
            List<Nametag.Segment> segments = new ArrayList<>();
            segments.add(new Nametag.Segment(name, nameColorArgb));
            if (displayHealth.get()) {
                float health = living.getHealth() + living.getAbsorptionAmount();
                float ratio = health / Math.max(1, living.getMaxHealth());
                int healthColor = ratio <= 0.333f ? 0xFFFF1919 : ratio <= 0.666f ? 0xFFFF6919 : 0xFF19FC19;
                String healthStr = String.format(Locale.ROOT, " %.0f", health);
                segments.add(new Nametag.Segment(healthStr, healthColor));
            }
            Nametag.drawSegments(graphics, point[0], point[1], segments, scale.floatValue(), true, backgroundColor.argb(), null);
        } else {
            Nametag.draw(graphics, point[0], point[1], List.of(new Nametag.Line(name, nameColorArgb)), scale.floatValue(), true, backgroundColor.argb(), -1, 0, null);
        }
    }

    private float[] project(double x, double y, double z) {
        vec.set((float) (x - cameraPos.x), (float) (y - cameraPos.y), (float) (z - cameraPos.z), 1f);
        viewProjection.transform(vec);
        if (vec.w <= 0.0001f) return null;
        return new float[] {(vec.x / vec.w + 1f) * 0.5f * screenWidth, (1f - vec.y / vec.w) * 0.5f * screenHeight};
    }

    private boolean onScreen(float[] p, int margin) {
        return p != null && p[0] >= -margin && p[0] <= screenWidth + margin && p[1] >= -margin && p[1] <= screenHeight + margin;
    }

    private List<ItemStack> equipment(Player player) {
        return List.of(player.getMainHandItem(), player.getItemBySlot(EquipmentSlot.HEAD), player.getItemBySlot(EquipmentSlot.CHEST),
            player.getItemBySlot(EquipmentSlot.LEGS), player.getItemBySlot(EquipmentSlot.FEET), player.getOffhandItem());
    }

    private List<List<Nametag.Line>> equipmentEnchantments(List<ItemStack> equipment) {
        if (!displayEnchants.get()) return java.util.Collections.nCopies(equipment.size(), List.of());
        List<List<Nametag.Line>> result = new ArrayList<>(equipment.size());
        for (ItemStack stack : equipment) {
            List<Nametag.Line> lines = new ArrayList<>();
            if (!stack.isEmpty()) {
                ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
                for (Holder<Enchantment> enchantment : enchants.keySet()) {
                    String id = enchantment.unwrapKey().map(key -> key.identifier().toString()).orElse("");
                    if (!shownEnchantments.contains(id)) continue;
                    String name = enchantment.value().description().getString();
                    if (name.length() > enchantNameLength.intValue()) name = name.substring(0, enchantNameLength.intValue());
                    int color = enchantment.is(EnchantmentTags.CURSE) ? 0xFFFF5555 : 0xFFFFFFFF;
                    lines.add(new Nametag.Line(name + " " + enchants.getLevel(enchantment), color));
                }
            }
            result.add(lines);
        }
        return result;
    }

    private List<String> equipmentDurability(List<ItemStack> equipment) {
        List<String> result = new ArrayList<>(equipment.size());
        for (ItemStack stack : equipment) {
            if (stack.isEmpty() || !stack.isDamageableItem() || itemDurability.get() == Durability.NONE) {
                result.add("");
                continue;
            }
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            result.add(itemDurability.get() == Durability.PERCENTAGE
                ? Math.round(remaining * 100f / stack.getMaxDamage()) + "%"
                : Integer.toString(remaining));
        }
        return result;
    }

    private int distanceColor(double distance) {
        if (distanceColorMode.get() == DistanceColorMode.FLAT) return distanceColor.argb();
        return Colors.lerp(0xFF19FC19, 0xFFFF1919, (float) Math.min(1, distance / 100.0));
    }

    private static String gameModeName(PlayerInfo info) {
        if (info == null || info.getGameMode() == null) return "BOT";
        return switch (info.getGameMode()) {
            case SURVIVAL -> "S";
            case CREATIVE -> "C";
            case ADVENTURE -> "A";
            case SPECTATOR -> "Sp";
        };
    }
}

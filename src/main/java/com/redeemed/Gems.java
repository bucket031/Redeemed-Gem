package com.redeemed;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Gems {
    public enum Stage {
        DORMANT("Dormant", NamedTextColor.DARK_GRAY),
        UNAWAKENED("Unawakened", NamedTextColor.GRAY),
        AWAKENING("Awakening", NamedTextColor.WHITE),
        AWAKENED("Awakened", NamedTextColor.WHITE);

        public final String label;
        public final TextColor color;
        Stage(String label, TextColor color) { this.label = label; this.color = color; }
        public String modelKey() { return "redeemedgem:" + name().toLowerCase(Locale.ROOT); }
    }

    public static final String[] DISGUISES = {"strength", "fire", "wealth", "speed", "puff", "astra", "life", "flux"};

    private final RedeemedGemPlugin pl;
    private final NamespacedKey stageKey, disguiseKey;

    public Gems(RedeemedGemPlugin pl) {
        this.pl = pl;
        this.stageKey = new NamespacedKey(pl, "rg_stage");
        this.disguiseKey = new NamespacedKey(pl, "rg_disguise");
    }

    public static String cap(String s) { return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    /** Builds a Redeemed Gem. Without the resource pack it shows as an enchanted prismarine shard. */
    public ItemStack create(Stage stage, String disguise) {
        ItemStack it = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(stageKey, PersistentDataType.STRING, stage.name());
        if (disguise != null) {
            m.getPersistentDataContainer().set(disguiseKey, PersistentDataType.STRING, disguise);
            m.displayName(Component.text(cap(disguise) + " Gem", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            m.setItemModel(NamespacedKey.fromString("redeemedgem:disguise_" + disguise));
        } else {
            m.displayName(Component.text("Redeemed Gem (" + stage.label + ")", stage.color).decoration(TextDecoration.ITALIC, false));
            m.setItemModel(NamespacedKey.fromString(stage.modelKey()));
            List<Component> lore = new ArrayList<>();
            lore.add(line("Drop it to disguise it as another gem."));
            if (stage.ordinal() >= 1) lore.add(line("Shackles - hit a target."));
            if (stage.ordinal() >= 2) lore.add(line("Shattered Slam - offhand + double right click."));
            if (stage.ordinal() >= 3) lore.add(line("Limitless - main hand right click."));
            m.lore(lore);
        }
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private static Component line(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    public Stage stageOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(stageKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Stage.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    public String disguiseOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(disguiseKey, PersistentDataType.STRING);
    }

    public boolean isRedeemed(ItemStack it) { return stageOf(it) != null; }

    /** Highest stage of any Redeemed Gem the player holds in a hand, or null. */
    public Stage heldStage(Player p) {
        Stage a = stageOf(p.getInventory().getItemInMainHand());
        Stage b = stageOf(p.getInventory().getItemInOffHand());
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Stage of the first Redeemed Gem found anywhere in the inventory, or null. */
    public Stage stageInInventory(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            Stage s = stageOf(it);
            if (s != null) return s;
        }
        return null;
    }

    /** Rewrites every Redeemed Gem the player has to the given stage (keeping any disguise). */
    public void setStage(Player p, Stage stage) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isRedeemed(it)) inv.setItem(i, create(stage, disguiseOf(it)));
        }
    }

    /** disguise == null reverts to the plain Redeemed Gem texture. */
    public void setDisguise(Player p, String disguise) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            Stage s = stageOf(it);
            if (s != null) inv.setItem(i, create(s, disguise));
        }
    }

    /** Detects a Bliss gem by display name (BlissPlugin is closed-source, so this is name based and configurable). */
    public boolean isGemLike(ItemStack it) {
        if (it == null || !it.hasItemMeta() || isRedeemed(it)) return false;
        Component dn = it.getItemMeta().displayName();
        if (dn == null) return false;
        String name = PlainTextComponentSerializer.plainText().serialize(dn).toLowerCase(Locale.ROOT);
        String req = pl.getConfig().getString("bliss-gem-detect.required-word", "gem").toLowerCase(Locale.ROOT);
        if (!req.isEmpty() && !name.contains(req)) return false;
        for (String n : pl.getConfig().getStringList("bliss-gem-detect.names")) {
            if (name.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Removes the Bliss gem(s) the player spawned with. Returns how many stacks were removed. */
    public int removeBlissGems(Player p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isGemLike(it)) { inv.setItem(i, null); n++; }
        }
        return n;
    }
}

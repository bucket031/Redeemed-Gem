package com.redeemed;

import com.redeemed.Gems.Stage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class RedeemedGemPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    public Gems gems;
    public TrustStore trust;
    public AbilityListener abilities;
    public MenuListener menus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        gems = new Gems(this);
        trust = new TrustStore(this);
        abilities = new AbilityListener(this);
        menus = new MenuListener(this);
        Bukkit.getPluginManager().registerEvents(abilities, this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("rg").setExecutor(this);
        getCommand("rg").setTabCompleter(this);
    }

    // ------------------------------------------------------------ /trust mirroring
    /** BlissPlugin owns /trust. We watch it and keep our own copy so Redeemed Gem abilities skip trusted players. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrustCommand(PlayerCommandPreprocessEvent e) {
        String[] a = e.getMessage().trim().split("\\s+");
        String label = a[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) label = label.substring(1);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (!label.equals("trust") || a.length < 2) return;
        UUID owner = e.getPlayer().getUniqueId();
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("clear")) { trust.clear(owner); return; }
        if (a.length < 3 || (!sub.equals("add") && !sub.equals("remove"))) return;
        OfflinePlayer o = Bukkit.getPlayerExact(a[2]);
        if (o == null) o = Bukkit.getOfflinePlayerIfCached(a[2]);
        if (o == null) return;
        if (sub.equals("add")) trust.add(owner, o.getUniqueId()); else trust.remove(owner, o.getUniqueId());
    }

    // ------------------------------------------------------------ Commands
    private static Component msg(String s) { return Component.text(s, NamedTextColor.GRAY); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { s.sendMessage(msg("/rg <start|trial|set|config|trust>")); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);

        if (sub.equals("trust")) { return trustCmd(s, a); }
        if (!s.hasPermission("redeemedgem.admin")) { s.sendMessage(Component.text("No permission.", NamedTextColor.RED)); return true; }

        switch (sub) {
            case "config" -> {
                if (!(s instanceof Player p)) { s.sendMessage(msg("Players only.")); return true; }
                menus.openConfig(p);
            }
            case "start" -> {
                Player t = target(s, a, 1);
                if (t == null) return true;
                int removed = gems.removeBlissGems(t);
                for (ItemStack left : t.getInventory().addItem(gems.create(Stage.DORMANT, null)).values())
                    t.getWorld().dropItem(t.getLocation(), left);
                t.playSound(t.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
                s.sendMessage(msg(t.getName() + " now has the Dormant Redeemed Gem (removed " + removed + " Bliss gem item(s))."));
            }
            case "trial" -> {
                if (a.length < 4 || !a[2].equalsIgnoreCase("start")) { s.sendMessage(msg("/rg trial <1-3> start <player>")); return true; }
                int n;
                try { n = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { n = 0; }
                if (n < 1 || n > 3) { s.sendMessage(msg("Trial must be 1, 2 or 3.")); return true; }
                Player t = target(s, a, 3);
                if (t == null) return true;
                trial(s, t, n);
            }
            case "set" -> {
                Player t = target(s, a, 1);
                if (t == null || a.length < 3) { s.sendMessage(msg("/rg set <player> <dormant|unawakened|awakening|awakened>")); return true; }
                try {
                    Stage st = Stage.valueOf(a[2].toUpperCase(Locale.ROOT));
                    gems.setStage(t, st);
                    s.sendMessage(msg("Set " + t.getName() + "'s Redeemed Gem to " + st.label + "."));
                } catch (IllegalArgumentException ex) { s.sendMessage(msg("Unknown stage.")); }
            }
            default -> s.sendMessage(msg("/rg <start|trial|set|config|trust>"));
        }
        return true;
    }

    private Player target(CommandSender s, String[] a, int idx) {
        if (a.length <= idx) { s.sendMessage(msg("Specify a player.")); return null; }
        Player t = Bukkit.getPlayerExact(a[idx]);
        if (t == null) s.sendMessage(msg("Player not online."));
        return t;
    }

    private void trial(CommandSender s, Player t, int n) {
        Stage cur = gems.stageInInventory(t);
        if (cur == null) { s.sendMessage(msg(t.getName() + " has no Redeemed Gem. Use /rg start first.")); return; }
        if (cur.ordinal() != n - 1) {
            s.sendMessage(msg(t.getName() + "'s gem is " + cur.label + "; trial " + n + " needs " + Stage.values()[n - 1].label + "."));
            return;
        }
        Stage next = Stage.values()[n];
        t.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20, 5, false, false, false));
        t.playSound(t.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.5f);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!t.isOnline()) return;
            t.removePotionEffect(PotionEffectType.LEVITATION);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false, false));
            gems.setStage(t, next);
            abilities.particleBurst(t.getLocation().add(0, 1, 0));
            t.playSound(t.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 1.5f);
            t.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text(next.label, next.color), Component.text("Redeemed Gem", NamedTextColor.GRAY)));
        }, 20L);
        s.sendMessage(msg("Trial " + n + " started for " + t.getName() + "."));
    }

    private boolean trustCmd(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage(msg("Players only.")); return true; }
        if (a.length < 2) { s.sendMessage(msg("/rg trust <add|remove|list|clear> [player]")); return true; }
        String act = a[1].toLowerCase(Locale.ROOT);
        switch (act) {
            case "clear" -> { trust.clear(p.getUniqueId()); s.sendMessage(msg("Redeemed Gem trust list cleared.")); }
            case "list" -> {
                List<String> names = new ArrayList<>();
                for (UUID u : trust.list(p.getUniqueId())) names.add(String.valueOf(Bukkit.getOfflinePlayer(u).getName()));
                s.sendMessage(msg("Trusted: " + (names.isEmpty() ? "nobody" : String.join(", ", names))));
            }
            case "add", "remove" -> {
                if (a.length < 3) { s.sendMessage(msg("Specify a player.")); return true; }
                OfflinePlayer o = Bukkit.getPlayerExact(a[2]);
                if (o == null) o = Bukkit.getOfflinePlayerIfCached(a[2]);
                if (o == null) { s.sendMessage(msg("Unknown player.")); return true; }
                if (act.equals("add")) trust.add(p.getUniqueId(), o.getUniqueId()); else trust.remove(p.getUniqueId(), o.getUniqueId());
                s.sendMessage(msg((act.equals("add") ? "Trusted " : "Untrusted ") + a[2] + "."));
            }
            default -> s.sendMessage(msg("/rg trust <add|remove|list|clear> [player]"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("start", "trial", "set", "config", "trust"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("trial")) out.addAll(List.of("1", "2", "3"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("trust")) out.addAll(List.of("add", "remove", "list", "clear"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("trial")) out.add("start");
        else if (a.length == 3 && a[0].equalsIgnoreCase("set")) for (Stage st : Stage.values()) out.add(st.name().toLowerCase(Locale.ROOT));
        else for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}

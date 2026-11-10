package com.redeemed;

import com.redeemed.Gems.Stage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class AbilityListener implements Listener {
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(10, 10, 10), 1.4f);
    private static final Particle.DustOptions GRAY = new Particle.DustOptions(Color.fromRGB(135, 135, 135), 1.2f);

    private final RedeemedGemPlugin pl;
    private final Map<UUID, Integer> charges = new HashMap<>();
    private final Map<UUID, Long> shackleReady = new HashMap<>();
    private final Map<UUID, Long> slamReady = new HashMap<>();
    private final Map<UUID, Long> limitReady = new HashMap<>();
    private final Map<UUID, Long> limitEnd = new HashMap<>();
    private final Map<UUID, Long> grounded = new HashMap<>();
    private final Map<UUID, Long> locked = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private boolean internalDamage;
    private int tick;

    public AbilityListener(RedeemedGemPlugin pl) {
        this.pl = pl;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 4L, 4L);
    }

    private double d(String path) { return pl.getConfig().getDouble(path); }
    private long ms(String secPath) { return (long) (d(secPath) * 1000.0); }
    private boolean isLocked(Player p, long now) { return locked.getOrDefault(p.getUniqueId(), 0L) > now; }
    private boolean isGrounded(LivingEntity e, long now) { return grounded.getOrDefault(e.getUniqueId(), 0L) > now; }

    private boolean validTarget(Player src, Player t) {
        return t != src && t.isOnline() && !t.isDead()
                && (t.getGameMode() == GameMode.SURVIVAL || t.getGameMode() == GameMode.ADVENTURE)
                && !pl.trust.isTrusted(src.getUniqueId(), t.getUniqueId());
    }

    // ------------------------------------------------------------------ Shackles
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (internalDamage) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity t)) return;
        Stage s = pl.gems.stageOf(p.getInventory().getItemInMainHand());
        if (s == null || s.ordinal() < 1) return;
        long now = System.currentTimeMillis();
        if (isLocked(p, now) || isGrounded(t, now)) return;
        if (t instanceof Player tp && pl.trust.isTrusted(p.getUniqueId(), tp.getUniqueId())) return;

        UUID id = p.getUniqueId();
        int max = (int) d("shackles.charges");
        int c = charges.getOrDefault(id, max);
        if (c <= 0) return;
        c--;
        charges.put(id, c);
        if (c == 0) shackleReady.put(id, now + ms("shackles.recharge-seconds"));

        ground(t, ms("shackles.ground-seconds"));
        trueDamage(t, d("shackles.damage-hearts") * 2.0, p);
        p.getWorld().playSound(t.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 0.6f);
    }

    private void ground(LivingEntity t, long millis) {
        grounded.put(t.getUniqueId(), System.currentTimeMillis() + millis);
        Location l = t.getLocation().add(0, 0.1, 0);
        ring(l, 0.8, 16);
        ring(l.clone().add(0, 0.9, 0), 0.8, 16);
        if (t instanceof Mob m) {
            boolean was = m.isAware();
            m.setAware(false);
            Bukkit.getScheduler().runTaskLater(pl, () -> m.setAware(was), Math.max(1L, millis / 50L));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isGrounded(p, System.currentTimeMillis())) return;
        Location f = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (f.getX() != to.getX() || f.getY() != to.getY() || f.getZ() != to.getZ()) {
            Location n = f.clone();
            n.setYaw(to.getYaw());
            n.setPitch(to.getPitch());
            e.setTo(n);
        }
    }

    // ------------------------------------------------------------------ Input
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        Player p = e.getPlayer();
        Stage s = pl.gems.stageOf(it);
        long now = System.currentTimeMillis();

        if (isLocked(p, now)) {
            if (s != null || pl.gems.isGemLike(it)) {
                e.setCancelled(true);
                p.sendActionBar(Component.text("Your abilities are locked!", NamedTextColor.GRAY));
            }
            return;
        }
        if (s == null) return;

        if (e.getHand() == EquipmentSlot.HAND) {
            if (s.ordinal() >= 3) { e.setCancelled(true); limitless(p, now); }
        } else if (e.getHand() == EquipmentSlot.OFF_HAND) {
            if (s.ordinal() >= 2) {
                e.setCancelled(true);
                UUID id = p.getUniqueId();
                long last = lastClick.getOrDefault(id, 0L);
                if (now - last <= (long) d("general.double-click-ms")) { lastClick.remove(id); slam(p, now); }
                else lastClick.put(id, now);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p, System.currentTimeMillis())) return;
        if (pl.gems.isGemLike(e.getMainHandItem()) || pl.gems.isGemLike(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropLocked(PlayerDropItemEvent e) {
        ItemStack it = e.getItemDrop().getItemStack();
        if (isLocked(e.getPlayer(), System.currentTimeMillis()) && pl.gems.isGemLike(it)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        grounded.remove(id); locked.remove(id); lastClick.remove(id); limitEnd.remove(id);
    }

    // ------------------------------------------------------------------ Shattered Slam
    private void slam(Player p, long now) {
        UUID id = p.getUniqueId();
        long ready = slamReady.getOrDefault(id, 0L);
        if (ready > now) { p.sendActionBar(Component.text("Shattered Slam: " + secs(ready - now) + "s", NamedTextColor.GRAY)); return; }
        slamReady.put(id, now + ms("slam.cooldown-seconds"));

        double r = d("slam.radius");
        Location c = p.getLocation().add(0, 0.15, 0);
        p.getWorld().playSound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.7f);
        p.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);

        new BukkitRunnable() {
            int step = 1;
            @Override public void run() {
                double rr = r * step / 8.0;
                ring(c, rr, Math.max(24, (int) (rr * 8)));
                if (++step > 8) cancel();
            }
        }.runTaskTimer(pl, 0L, 1L);

        for (Player t : p.getWorld().getNearbyPlayers(p.getLocation(), r)) {
            if (!validTarget(p, t)) continue;
            Vector dir = t.getLocation().toVector().subtract(p.getLocation().toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
            dir.normalize().multiply(d("slam.fling-distance") / 8.0).setY(0.7);
            t.setVelocity(dir);
            trueDamage(t, d("slam.damage-hearts") * 2.0, p);
        }
    }

    // ------------------------------------------------------------------ Limitless
    private void limitless(Player p, long now) {
        UUID id = p.getUniqueId();
        long ready = limitReady.getOrDefault(id, 0L);
        if (ready > now) { p.sendActionBar(Component.text("Limitless: " + secs(ready - now) + "s", NamedTextColor.GRAY)); return; }
        long dur = ms("limitless.duration-seconds");
        limitEnd.put(id, now + dur);
        limitReady.put(id, now + dur + ms("limitless.cooldown-seconds"));

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        double take = d("limitless.hearts-taken") * 2.0, gained = 0;
        for (Player t : p.getWorld().getNearbyPlayers(p.getLocation(), d("limitless.radius"))) {
            if (!validTarget(p, t)) continue;
            double before = t.getHealth();
            trueDamage(t, take, p);
            gained += Math.min(take, before);
            line(t.getLocation().add(0, 1, 0), p.getLocation().add(0, 1, 0));
        }
        if (gained > 0) {
            double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            p.setHealth(Math.min(max, p.getHealth() + gained));
        }
    }

    private void tickLimitless(Player p, long now) {
        ring(p.getLocation().add(0, 0.1, 0), d("limitless.radius"), 48);
        double lr = d("limitless.lock-radius");
        for (Player t : p.getWorld().getNearbyPlayers(p.getLocation(), lr)) {
            if (!validTarget(p, t)) continue;
            locked.put(t.getUniqueId(), now + ms("limitless.lock-seconds"));
            t.getWorld().spawnParticle(Particle.DUST, t.getLocation().add(0, 1, 0), 6, 0.3, 0.6, 0.3, 0, GRAY);
        }
        if (tick % 5 == 0) {
            int amt = (int) d("limitless.armor-mend-per-second");
            ItemStack[] armor = p.getInventory().getArmorContents();
            for (ItemStack a : armor) {
                if (a != null && a.getItemMeta() instanceof Damageable dm && dm.hasDamage()) {
                    dm.setDamage(Math.max(0, dm.getDamage() - amt));
                    a.setItemMeta(dm);
                }
            }
            p.getInventory().setArmorContents(armor);
        }
    }

    // ------------------------------------------------------------------ Tick / HUD
    private void tick() {
        long now = System.currentTimeMillis();
        tick++;
        shackleReady.entrySet().removeIf(en -> {
            if (now >= en.getValue()) { charges.remove(en.getKey()); return true; }
            return false;
        });
        grounded.values().removeIf(v -> v <= now);
        locked.values().removeIf(v -> v <= now);

        Iterator<Map.Entry<UUID, Long>> it = limitEnd.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead() || now >= en.getValue()) { it.remove(); continue; }
            tickLimitless(p, now);
        }
        if (tick % 5 == 0) hud(now);
    }

    private void hud(long now) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Stage s = pl.gems.heldStage(p);
            if (s == null || s == Stage.DORMANT) continue;
            UUID id = p.getUniqueId();
            List<Component> parts = new ArrayList<>();
            int max = (int) d("shackles.charges");
            long sr = shackleReady.getOrDefault(id, 0L);
            parts.add(part("Shackles", sr > now ? secs(sr - now) + "s" : charges.getOrDefault(id, max) + "/" + max));
            if (s.ordinal() >= 3) {
                long end = limitEnd.getOrDefault(id, 0L), lr = limitReady.getOrDefault(id, 0L);
                parts.add(part("Limitless", end > now ? "ACTIVE " + secs(end - now) + "s" : lr > now ? secs(lr - now) + "s" : "READY"));
            }
            if (s.ordinal() >= 2) {
                long sl = slamReady.getOrDefault(id, 0L);
                parts.add(part("Shattered Slam", sl > now ? secs(sl - now) + "s" : "READY"));
            }
            p.sendActionBar(Component.join(JoinConfiguration.separator(Component.text(" | ", NamedTextColor.DARK_GRAY)), parts));
        }
    }

    private Component part(String name, String state) {
        return Component.text(name + " ", NamedTextColor.GRAY).append(Component.text(state, NamedTextColor.WHITE));
    }

    private static long secs(long millis) { return (millis + 999) / 1000; }

    // ------------------------------------------------------------------ Helpers
    /** Damage that ignores armor and enchantments. Falls back to a lethal hit so totems and kill credit still work. */
    public void trueDamage(LivingEntity t, double hp, Player src) {
        if (t.isDead() || hp <= 0) return;
        internalDamage = true;
        try {
            double left = t.getHealth() - hp;
            if (left <= 0) {
                t.damage(10000.0, src);
            } else {
                t.setHealth(left);
                t.playEffect(EntityEffect.HURT);
            }
        } finally {
            internalDamage = false;
        }
    }

    private void ring(Location c, double r, int pts) {
        World w = c.getWorld();
        for (int i = 0; i < pts; i++) {
            double a = 2 * Math.PI * i / pts;
            w.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, i % 2 == 0 ? BLACK : GRAY);
        }
    }

    private void line(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector());
        double len = v.length();
        if (len < 0.1) return;
        v.normalize();
        for (double i = 0; i < len; i += 0.4) {
            from.getWorld().spawnParticle(Particle.DUST, from.clone().add(v.clone().multiply(i)), 1, 0, 0, 0, 0, ((int) (i / 0.4)) % 2 == 0 ? BLACK : GRAY);
        }
    }

    public void particleBurst(Location c) {
        World w = c.getWorld();
        w.spawnParticle(Particle.DUST, c, 40, 0.5, 0.8, 0.5, 0, BLACK);
        w.spawnParticle(Particle.DUST, c, 40, 0.5, 0.8, 0.5, 0, GRAY);
    }
}

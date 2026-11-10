package com.redeemed;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Mirror of each player's /trust list so Redeemed Gem abilities skip trusted players. */
public final class TrustStore {
    private final RedeemedGemPlugin pl;
    private final File file;
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();

    public TrustStore(RedeemedGemPlugin pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "trust.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                Set<UUID> set = new HashSet<>();
                for (String s : y.getStringList(k)) set.add(UUID.fromString(s));
                trusted.put(UUID.fromString(k), set);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> e : trusted.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) l.add(u.toString());
            y.set(e.getKey().toString(), l);
        }
        try { y.save(file); } catch (IOException ex) { pl.getLogger().warning("Could not save trust.yml: " + ex.getMessage()); }
    }

    public boolean isTrusted(UUID owner, UUID other) {
        Set<UUID> s = trusted.get(owner);
        return s != null && s.contains(other);
    }

    public void add(UUID owner, UUID other) { trusted.computeIfAbsent(owner, k -> new HashSet<>()).add(other); save(); }
    public void remove(UUID owner, UUID other) { Set<UUID> s = trusted.get(owner); if (s != null) { s.remove(other); save(); } }
    public void clear(UUID owner) { trusted.remove(owner); save(); }
    public Set<UUID> list(UUID owner) { return trusted.getOrDefault(owner, Collections.emptySet()); }
}

package io.wifi.habitrainsurvive.security;

import net.minecraft.commands.CommandSourceStack;
import java.security.SecureRandom;
import java.util.*;

public final class Confirmations {
    private record Pending(String token, long expires) {}
    private static final Map<String, Pending> pending = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private Confirmations() {}
    private static String key(CommandSourceStack source, String action) {
        return (source.getPlayer() == null ? "console" : source.getPlayer().getUUID().toString()) + ":" + action;
    }
    public static String issue(CommandSourceStack source, String action) {
        pending.entrySet().removeIf(e -> e.getValue().expires < System.currentTimeMillis());
        String token = String.format("%06x", RANDOM.nextInt(0x1000000));
        pending.put(key(source, action), new Pending(token, System.currentTimeMillis() + 30000)); return token;
    }
    public static boolean consume(CommandSourceStack source, String action, String token) {
        String key = key(source, action); Pending p = pending.get(key);
        if (p == null || p.expires < System.currentTimeMillis() || !p.token.equals(token)) return false;
        pending.remove(key); return true;
    }
    public static void clear() { pending.clear(); }
}

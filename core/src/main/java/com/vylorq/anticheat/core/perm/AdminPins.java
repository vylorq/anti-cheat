package com.vylorq.anticheat.core.perm;

import com.vylorq.anticheat.core.util.Clock;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin PINs (section 15). PINs are stored as PBKDF2 hashes. Admin powers are locked until a correct
 * {@code /login <pin>} each session; too many wrong attempts lock the account for a while.
 */
public final class AdminPins {
    public enum Result { OK, WRONG, LOCKED, NO_PIN_SET, INVALID_FORMAT }

    /** Stored per admin. */
    public static final class Entry {
        public String salt;
        public String hash;
        public int failed;
        public long lockedUntil;
    }

    private static final int ITERATIONS = 120_000;
    private final Map<UUID, Entry> pins;
    private final Set<UUID> loggedIn = new HashSet<>();
    private final Clock clock;
    private final int maxAttempts;
    private final long lockMillis;

    public AdminPins(Map<UUID, Entry> stored, Clock clock, int maxAttempts, long lockMillis) {
        this.pins = stored;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.lockMillis = lockMillis;
    }

    public Map<UUID, Entry> data() {
        return pins;
    }

    public static boolean validFormat(String pin) {
        return pin != null && pin.matches("\\d{4,12}");
    }

    public boolean hasPin(UUID id) {
        return pins.containsKey(id);
    }

    public Result setPin(UUID id, String pin) {
        if (!validFormat(pin)) {
            return Result.INVALID_FORMAT;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Entry e = new Entry();
        e.salt = Base64.getEncoder().encodeToString(salt);
        e.hash = hash(pin, salt);
        pins.put(id, e);
        loggedIn.add(id);
        return Result.OK;
    }

    public void clearPin(UUID id) {
        pins.remove(id);
        loggedIn.remove(id);
    }

    public Result login(UUID id, String pin) {
        Entry e = pins.get(id);
        if (e == null) {
            return Result.NO_PIN_SET;
        }
        long now = clock.nowMillis();
        if (e.lockedUntil > now) {
            return Result.LOCKED;
        }
        byte[] salt = Base64.getDecoder().decode(e.salt);
        String h = hash(pin == null ? "" : pin, salt);
        if (MessageDigest.isEqual(h.getBytes(), e.hash.getBytes())) {
            e.failed = 0;
            loggedIn.add(id);
            return Result.OK;
        }
        e.failed++;
        if (e.failed >= maxAttempts) {
            e.failed = 0;
            e.lockedUntil = now + lockMillis;
            return Result.LOCKED;
        }
        return Result.WRONG;
    }

    public boolean isLoggedIn(UUID id) {
        return loggedIn.contains(id);
    }

    public void logout(UUID id) {
        loggedIn.remove(id);
    }

    public int failedAttempts(UUID id) {
        Entry e = pins.get(id);
        return e == null ? 0 : e.failed;
    }

    private static String hash(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }
}

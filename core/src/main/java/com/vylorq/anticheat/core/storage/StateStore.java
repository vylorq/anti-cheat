package com.vylorq.anticheat.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Saves each module's state as a JSON document in the database. */
public final class StateStore {
    public static final Gson GSON = new GsonBuilder().enableComplexMapKeySerialization().serializeSpecialFloatingPointValues().create();

    private final Database db;
    private final Consumer<Exception> errors;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AntiCheat-StateWriter");
        t.setDaemon(true);
        return t;
    });

    public StateStore(Database db, Consumer<Exception> errors) {
        this.db = db;
        this.errors = errors;
    }

    public <T> T load(String key, Class<T> type, T fallback) {
        try {
            String json = db.getState(key);
            if (json == null) {
                return fallback;
            }
            T v = GSON.fromJson(json, type);
            return v == null ? fallback : v;
        } catch (Exception e) {
            errors.accept(new RuntimeException("Could not load state '" + key + "'", e));
            return fallback;
        }
    }

    /**
     * Serializes on the calling thread (a consistent snapshot, call it from the server thread) and writes it on a
     * background thread so the server never waits on disk.
     */
    public void save(String key, Object value) {
        final String json;
        try {
            json = GSON.toJson(value);
        } catch (Exception e) {
            errors.accept(new RuntimeException("Could not serialize state '" + key + "'", e));
            return;
        }
        writer.execute(() -> write(key, json));
    }

    /** Writes immediately (used at shutdown). */
    public void saveNow(String key, Object value) {
        try {
            write(key, GSON.toJson(value));
        } catch (Exception e) {
            errors.accept(new RuntimeException("Could not save state '" + key + "'", e));
        }
    }

    private void write(String key, String json) {
        try {
            db.putState(key, json);
        } catch (Exception e) {
            errors.accept(new RuntimeException("Could not save state '" + key + "'", e));
        }
    }

    /** Waits for queued writes. */
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

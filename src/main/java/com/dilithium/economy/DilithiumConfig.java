package com.dilithium.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON configuration for the Dilithium Economy mod.
 * Auto-created with defaults if missing.
 */
public final class DilithiumConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String node_url = "http://localhost:8001";
    private String network_name = "dilithium-mainnet";
    private String encryption_passphrase = "";
    private int sync_interval_seconds = 30;
    private long default_fee_base_units = 10_000;
    private int max_pending_seconds = 300;

    public String nodeUrl() { return node_url; }
    public String networkName() { return network_name; }
    public String encryptionPassphrase() { return encryption_passphrase; }
    public int syncIntervalSeconds() { return sync_interval_seconds; }
    public long defaultFeeBaseUnits() { return default_fee_base_units; }
    public int maxPendingSeconds() { return max_pending_seconds; }

    /**
     * Loads config from file, or creates a default config file if it doesn't exist.
     * Returns the absolute path of the config file for logging.
     */
    public static LoadResult load(Path dataDir) {
        Path configFile = dataDir.resolve("config.json");
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                DilithiumConfig config = GSON.fromJson(json, DilithiumConfig.class);
                if (config != null) return new LoadResult(config, configFile);
            } catch (IOException e) {
                System.err.println("[DilithiumEconomy] Failed to read config: " + e.getMessage());
            }
        }
        // Create default
        DilithiumConfig config = new DilithiumConfig();
        config.save(dataDir);
        return new LoadResult(config, configFile);
    }

    /**
     * Saves the config to file.
     */
    public void save(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Path configFile = dataDir.resolve("config.json");
            Files.writeString(configFile, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[DilithiumEconomy] Failed to save config: " + e.getMessage());
        }
    }

    public record LoadResult(DilithiumConfig config, Path configPath) {}
}

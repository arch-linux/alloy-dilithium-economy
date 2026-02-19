package com.dilithium.economy.wallet;

import com.dilithium.economy.crypto.DilithiumCrypto;
import com.dilithium.economy.crypto.WalletData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player wallets: CRUD operations, UUID↔address lookup, AES-256-GCM encrypted persistence.
 */
public final class WalletManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int KEY_STRETCH_ITERATIONS = 100_000;

    /** UUID → WalletData (in-memory) */
    private final ConcurrentHashMap<UUID, WalletData> wallets = new ConcurrentHashMap<>();

    /** address → UUID reverse lookup */
    private final ConcurrentHashMap<String, UUID> addressToPlayer = new ConcurrentHashMap<>();

    private final Path dataDir;
    private final String passphrase;

    public WalletManager(Path dataDir, String passphrase) {
        this.dataDir = dataDir;
        this.passphrase = passphrase;
        load();
    }

    /**
     * Creates a new wallet for a player. If one already exists, returns the existing address.
     */
    public String createWallet(UUID playerId) {
        if (wallets.containsKey(playerId)) {
            return wallets.get(playerId).address();
        }
        WalletData wallet = DilithiumCrypto.generateKeyPair();
        wallets.put(playerId, wallet);
        addressToPlayer.put(wallet.address(), playerId);
        save();
        return wallet.address();
    }

    /**
     * Imports an external private key, replacing any existing wallet for this player.
     */
    public String importWallet(UUID playerId, String hexPrivateKey) {
        // Remove old mapping if exists
        WalletData old = wallets.get(playerId);
        if (old != null) {
            addressToPlayer.remove(old.address());
        }

        WalletData wallet = DilithiumCrypto.importFromPrivateKeyHex(hexPrivateKey);
        wallets.put(playerId, wallet);
        addressToPlayer.put(wallet.address(), playerId);
        save();
        return wallet.address();
    }

    /**
     * Returns the wallet address for a player, or null if no wallet exists.
     */
    public String getAddress(UUID playerId) {
        WalletData wallet = wallets.get(playerId);
        return wallet != null ? wallet.address() : null;
    }

    /**
     * Returns the full wallet data for a player, or null.
     */
    public WalletData getWallet(UUID playerId) {
        return wallets.get(playerId);
    }

    /**
     * Returns the player UUID for a given address, or null.
     */
    public UUID getPlayerByAddress(String address) {
        return addressToPlayer.get(address);
    }

    /**
     * Checks if a player has a wallet.
     */
    public boolean hasWallet(UUID playerId) {
        return wallets.containsKey(playerId);
    }

    /**
     * Returns all known wallet addresses (for balance syncing).
     */
    public java.util.Set<String> allAddresses() {
        return addressToPlayer.keySet();
    }

    // ========================================================================
    // Encrypted persistence
    // ========================================================================

    /**
     * Serializable wallet entry for JSON storage.
     */
    private record StoredWallet(String uuid, String privateKeyHex, String publicKeyHex, String address) {}

    private void save() {
        try {
            Files.createDirectories(dataDir);
            var entries = wallets.entrySet().stream().map(e -> new StoredWallet(
                    e.getKey().toString(),
                    DilithiumCrypto.bytesToHex(e.getValue().privateKeyBytes()),
                    DilithiumCrypto.bytesToHex(e.getValue().publicKeyBytes()),
                    e.getValue().address()
            )).toList();

            String json = GSON.toJson(entries);

            if (passphrase != null && !passphrase.isEmpty()) {
                String encrypted = encrypt(json, passphrase);
                Files.writeString(dataDir.resolve("wallets.enc"), encrypted, StandardCharsets.UTF_8);
            } else {
                Files.writeString(dataDir.resolve("wallets.json"), json, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Failed to save wallets: " + e.getMessage());
        }
    }

    private void load() {
        try {
            Path encFile = dataDir.resolve("wallets.enc");
            Path jsonFile = dataDir.resolve("wallets.json");
            String json = null;

            if (Files.exists(encFile) && passphrase != null && !passphrase.isEmpty()) {
                String encrypted = Files.readString(encFile, StandardCharsets.UTF_8);
                json = decrypt(encrypted, passphrase);
            } else if (Files.exists(jsonFile)) {
                json = Files.readString(jsonFile, StandardCharsets.UTF_8);
            }

            if (json != null) {
                Type listType = new TypeToken<java.util.List<StoredWallet>>(){}.getType();
                java.util.List<StoredWallet> entries = GSON.fromJson(json, listType);
                if (entries != null) {
                    for (StoredWallet sw : entries) {
                        UUID uuid = UUID.fromString(sw.uuid());
                        byte[] privKey = DilithiumCrypto.hexToBytes(sw.privateKeyHex());
                        byte[] pubKey = DilithiumCrypto.hexToBytes(sw.publicKeyHex());
                        WalletData wallet = new WalletData(privKey, pubKey, sw.address());
                        wallets.put(uuid, wallet);
                        addressToPlayer.put(sw.address(), uuid);
                    }
                    System.out.println("[DilithiumEconomy] Loaded " + wallets.size() + " wallets");
                }
            }
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Failed to load wallets: " + e.getMessage());
        }
    }

    // ========================================================================
    // AES-256-GCM encryption (matches Go scheme: SHA-256 key stretch, salt + nonce + ciphertext)
    // ========================================================================

    private static byte[] deriveKey(String passphrase, byte[] salt) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] key = sha256.digest((passphrase + new String(salt, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < KEY_STRETCH_ITERATIONS; i++) {
            key = sha256.digest(key);
        }
        return key;
    }

    private static String encrypt(String plaintext, String passphrase) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);

        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);

        byte[] key = deriveKey(passphrase, salt);
        SecretKey secretKey = new SecretKeySpec(key, "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Concatenate: salt(16) + nonce(12) + ciphertext
        byte[] combined = new byte[SALT_BYTES + NONCE_BYTES + ciphertext.length];
        System.arraycopy(salt, 0, combined, 0, SALT_BYTES);
        System.arraycopy(nonce, 0, combined, SALT_BYTES, NONCE_BYTES);
        System.arraycopy(ciphertext, 0, combined, SALT_BYTES + NONCE_BYTES, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private static String decrypt(String encoded, String passphrase) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encoded);

        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = new byte[combined.length - SALT_BYTES - NONCE_BYTES];

        System.arraycopy(combined, 0, salt, 0, SALT_BYTES);
        System.arraycopy(combined, SALT_BYTES, nonce, 0, NONCE_BYTES);
        System.arraycopy(combined, SALT_BYTES + NONCE_BYTES, ciphertext, 0, ciphertext.length);

        byte[] key = deriveKey(passphrase, salt);
        SecretKey secretKey = new SecretKeySpec(key, "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext, StandardCharsets.UTF_8);
    }
}

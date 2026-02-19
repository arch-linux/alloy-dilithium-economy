package com.dilithium.economy.wallet;

import com.dilithium.economy.crypto.DilithiumCrypto;
import com.dilithium.economy.crypto.WalletData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The server's reserve wallet. Used for deposits (reserve → player)
 * and as the destination for withdrawals (player → reserve).
 * Auto-created on first launch, persisted as plaintext (public info + private key).
 */
public final class ReserveWallet {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final WalletData wallet;

    private ReserveWallet(WalletData wallet) {
        this.wallet = wallet;
    }

    /**
     * Loads or creates the reserve wallet.
     */
    public static ReserveWallet loadOrCreate(Path dataDir) {
        Path reserveFile = dataDir.resolve("reserve_wallet.json");
        if (Files.exists(reserveFile)) {
            try {
                String json = Files.readString(reserveFile, StandardCharsets.UTF_8);
                JsonObject obj = GSON.fromJson(json, JsonObject.class);
                byte[] privKey = DilithiumCrypto.hexToBytes(obj.get("private_key").getAsString());
                byte[] pubKey = DilithiumCrypto.hexToBytes(obj.get("public_key").getAsString());
                String address = obj.get("address").getAsString();
                return new ReserveWallet(new WalletData(privKey, pubKey, address));
            } catch (Exception e) {
                System.err.println("[DilithiumEconomy] Failed to load reserve wallet, generating new: " + e.getMessage());
            }
        }

        // Generate new reserve wallet
        WalletData wallet = DilithiumCrypto.generateKeyPair();
        ReserveWallet reserve = new ReserveWallet(wallet);
        reserve.save(dataDir);
        return reserve;
    }

    private void save(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            JsonObject obj = new JsonObject();
            obj.addProperty("address", wallet.address());
            obj.addProperty("public_key", DilithiumCrypto.bytesToHex(wallet.publicKeyBytes()));
            obj.addProperty("private_key", DilithiumCrypto.bytesToHex(wallet.privateKeyBytes()));
            Files.writeString(dataDir.resolve("reserve_wallet.json"), GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Failed to save reserve wallet: " + e.getMessage());
        }
    }

    public WalletData wallet() { return wallet; }
    public String address() { return wallet.address(); }
}

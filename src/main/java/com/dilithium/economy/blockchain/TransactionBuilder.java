package com.dilithium.economy.blockchain;

import com.dilithium.economy.crypto.DilithiumCrypto;
import com.dilithium.economy.crypto.WalletData;

/**
 * Builds, signs, and submits Dilithium blockchain transactions.
 * Signing data format must match Go exactly:
 * "dilithium-mainnet:" + from + to + amount + fee + timestamp
 * (concatenated, no separators between fields after the colon)
 */
public final class TransactionBuilder {

    /** 1 DLT = 100,000,000 base units (same as Go DLTUnit) */
    public static final long DLT_UNIT = 100_000_000L;

    private TransactionBuilder() {}

    /**
     * Builds a signing data string that matches the Go node's expected format.
     */
    public static String buildSigningData(String networkName, String from, String to,
                                          long amount, long fee, long timestamp) {
        return networkName + ":" + from + to + amount + fee + timestamp;
    }

    /**
     * Signs and submits a transaction to the blockchain.
     * @return true if the node accepted the transaction
     */
    public static boolean buildAndSubmit(WalletData sender, String toAddr, long amount, long fee,
                                         String networkName, BlockchainClient client) {
        long timestamp = System.currentTimeMillis() / 1000;
        String txData = buildSigningData(networkName, sender.address(), toAddr, amount, fee, timestamp);
        String signature = DilithiumCrypto.sign(sender.privateKeyBytes(), txData);
        // The blockchain expects raw public key bytes (not SPKI-encoded)
        byte[] rawPubKey = DilithiumCrypto.getRawPublicKey(sender.privateKeyBytes());
        String publicKeyHex = DilithiumCrypto.bytesToHex(rawPubKey);

        return client.submitTransaction(sender.address(), toAddr, amount, fee,
                timestamp, signature, publicKeyHex);
    }

    /**
     * Converts a double DLT amount to base units (long).
     * E.g., 1.5 DLT → 150000000
     */
    public static long toBaseUnits(double dlt) {
        return Math.round(dlt * DLT_UNIT);
    }

    /**
     * Converts base units (long) to a double DLT amount.
     */
    public static double toDLT(long baseUnits) {
        return (double) baseUnits / DLT_UNIT;
    }

    /**
     * Formats base units as a human-readable DLT string with 8 decimal places.
     * Matches Go's FormatDLT: "whole.fractional" with zero-padded 8 digits.
     */
    public static String formatDLT(long baseUnits) {
        long whole = baseUnits / DLT_UNIT;
        long frac = Math.abs(baseUnits % DLT_UNIT);
        return String.format("%d.%08d", whole, frac);
    }
}

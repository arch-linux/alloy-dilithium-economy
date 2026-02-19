package com.dilithium.economy.crypto;

/**
 * Holds a Dilithium wallet's key material and derived address.
 *
 * @param privateKeyBytes raw Dilithium Mode3 private key (4000 bytes)
 * @param publicKeyBytes  raw Dilithium Mode3 public key (1952 bytes)
 * @param address         40-hex-char address derived from SHA-256(publicKeyBytes)
 */
public record WalletData(byte[] privateKeyBytes, byte[] publicKeyBytes, String address) {}

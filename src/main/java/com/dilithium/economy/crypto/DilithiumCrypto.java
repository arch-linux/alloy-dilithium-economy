package com.dilithium.economy.crypto;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyPairGenerator;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner;
import org.bouncycastle.pqc.crypto.util.PrivateKeyFactory;
import org.bouncycastle.pqc.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.pqc.crypto.util.PublicKeyFactory;
import org.bouncycastle.pqc.crypto.util.SubjectPublicKeyInfoFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * All Dilithium Mode3 crypto operations: key generation, address derivation, signing.
 * Must produce signatures compatible with Go's cloudflare/circl mode3 implementation.
 *
 * Keys are stored in PKCS#8 (private) and X.509 SPKI (public) encoded form for reliable
 * serialization/deserialization through BouncyCastle's key factories.
 */
public final class DilithiumCrypto {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private DilithiumCrypto() {}

    /**
     * Generates a new Dilithium Mode3 key pair.
     * @return WalletData containing PKCS#8 private key, X509 public key, and derived address
     */
    public static WalletData generateKeyPair() {
        DilithiumKeyPairGenerator generator = new DilithiumKeyPairGenerator();
        generator.init(new DilithiumKeyGenerationParameters(SECURE_RANDOM, DilithiumParameters.dilithium3));
        AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();

        DilithiumPrivateKeyParameters privKey = (DilithiumPrivateKeyParameters) keyPair.getPrivate();
        DilithiumPublicKeyParameters pubKey = (DilithiumPublicKeyParameters) keyPair.getPublic();

        try {
            byte[] privateKeyBytes = PrivateKeyInfoFactory.createPrivateKeyInfo(privKey).getEncoded();
            byte[] publicKeyBytes = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pubKey).getEncoded();

            // Address is derived from the RAW public key bytes (matching Go's pubKeyBytes via MarshalBinary)
            byte[] rawPubKey = pubKey.getEncoded();
            String address = deriveAddress(rawPubKey);

            return new WalletData(privateKeyBytes, publicKeyBytes, address);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode Dilithium keys", e);
        }
    }

    /**
     * Derives a 40-character hex address from raw public key bytes.
     * Matches Go: hex(SHA-256(pubKeyBytes))[:40]
     */
    public static String deriveAddress(byte[] rawPublicKeyBytes) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(rawPublicKeyBytes);
            return bytesToHex(hash).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Signs transaction data with the given PKCS#8-encoded private key.
     * @param pkcs8PrivateKey PKCS#8 encoded Dilithium Mode3 private key
     * @param txData the signing data string (UTF-8 encoded)
     * @return hex-encoded signature
     */
    public static String sign(byte[] pkcs8PrivateKey, String txData) {
        try {
            DilithiumPrivateKeyParameters privKey = (DilithiumPrivateKeyParameters)
                    PrivateKeyFactory.createKey(pkcs8PrivateKey);

            DilithiumSigner signer = new DilithiumSigner();
            signer.init(true, privKey);
            byte[] signature = signer.generateSignature(txData.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(signature);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign transaction", e);
        }
    }

    /**
     * Returns the raw (non-SPKI) public key bytes for inclusion in blockchain transactions.
     * This matches Go's publicKey.MarshalBinary() output.
     */
    public static byte[] getRawPublicKey(byte[] pkcs8PrivateKey) {
        try {
            DilithiumPrivateKeyParameters privKey = (DilithiumPrivateKeyParameters)
                    PrivateKeyFactory.createKey(pkcs8PrivateKey);
            return privKey.getPublicKeyParameters().getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract public key", e);
        }
    }

    /**
     * Imports a wallet from a hex-encoded PKCS#8 private key.
     * Derives the public key and address from the private key.
     */
    public static WalletData importFromPrivateKeyHex(String hexPrivateKey) {
        try {
            byte[] pkcs8Bytes = hexToBytes(hexPrivateKey);
            DilithiumPrivateKeyParameters privKey = (DilithiumPrivateKeyParameters)
                    PrivateKeyFactory.createKey(pkcs8Bytes);
            DilithiumPublicKeyParameters pubKey = privKey.getPublicKeyParameters();

            byte[] privateKeyBytes = pkcs8Bytes;
            byte[] publicKeyBytes = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pubKey).getEncoded();
            byte[] rawPubKey = pubKey.getEncoded();
            String address = deriveAddress(rawPubKey);

            return new WalletData(privateKeyBytes, publicKeyBytes, address);
        } catch (Exception e) {
            throw new RuntimeException("Failed to import private key: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a byte array to a lowercase hex string.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Converts a hex string to a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}

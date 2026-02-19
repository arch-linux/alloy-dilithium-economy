package com.dilithium.economy.commands;

import com.dilithium.economy.blockchain.TransactionBuilder;
import com.dilithium.economy.crypto.DilithiumCrypto;
import com.dilithium.economy.crypto.WalletData;
import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.util.BookHelper;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.command.Command;
import net.alloymc.api.command.CommandSender;
import net.alloymc.api.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /dilithium command — wallet management hub.
 * Subcommands: help, address, key, balance, pending, import, export
 */
public final class DilithiumCommand extends Command {

    private static final long BOOK_COOLDOWN_MS = 2 * 60 * 60 * 1000L; // 2 hours

    private final WalletManager walletManager;
    private final BalanceCache balanceCache;
    private final Path dataDir;
    private final ConcurrentHashMap<UUID, Long> bookCooldowns = new ConcurrentHashMap<>();

    public DilithiumCommand(WalletManager walletManager, BalanceCache balanceCache, Path dataDir) {
        super("dilithium", "Dilithium wallet commands", "dilithium.wallet");
        this.walletManager = walletManager;
        this.balanceCache = balanceCache;
        this.dataDir = dataDir;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.isPlayer()) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        UUID playerId = player.uniqueId();

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "address" -> {
                String address = walletManager.getAddress(playerId);
                if (address == null) {
                    player.sendMessage("You don't have a wallet yet. Rejoin the server to create one.");
                } else {
                    player.sendMessage("Your wallet address:");
                    player.sendRichMessage(address, address, "Click to copy address", 0xFFAA00);
                }
            }
            case "key" -> {
                WalletData wallet = walletManager.getWallet(playerId);
                if (wallet == null) {
                    player.sendMessage("You don't have a wallet yet. Rejoin the server to create one.");
                } else {
                    byte[] rawPubKey = DilithiumCrypto.getRawPublicKey(wallet.privateKeyBytes());
                    String fullHex = DilithiumCrypto.bytesToHex(rawPubKey);
                    String truncated = fullHex.substring(0, 32) + "...";
                    player.sendMessage("Your public key:");
                    player.sendRichMessage(truncated, fullHex, "Click to copy full public key", 0x55FFFF);
                }
            }
            case "balance" -> {
                WalletData wallet = walletManager.getWallet(playerId);
                if (wallet == null) {
                    player.sendMessage("You don't have a wallet yet. Rejoin the server to create one.");
                } else {
                    long effective = balanceCache.getBalance(wallet.address());
                    long pendingTotal = balanceCache.getTotalPendingDebits(wallet.address());
                    player.sendMessage("Balance: " + TransactionBuilder.formatDLT(effective) + " DLT");
                    if (pendingTotal > 0) {
                        player.sendMessage("  Pending outgoing: " + TransactionBuilder.formatDLT(pendingTotal) + " DLT");
                        player.sendMessage("  (funds locked until transactions are mined)");
                    }
                    if (!balanceCache.isNodeOnline()) {
                        player.sendMessage("(Warning: blockchain node is offline, balance may be stale)");
                    }
                }
            }
            case "pending" -> {
                WalletData wallet = walletManager.getWallet(playerId);
                if (wallet == null) {
                    player.sendMessage("You don't have a wallet yet. Rejoin the server to create one.");
                } else {
                    List<BalanceCache.PendingDebit> pending = balanceCache.getPendingDebits(wallet.address());
                    if (pending.isEmpty()) {
                        player.sendMessage("No pending transactions.");
                    } else {
                        player.sendMessage("=== Pending Transactions ===");
                        for (BalanceCache.PendingDebit d : pending) {
                            long ageSeconds = (System.currentTimeMillis() - d.createdAt()) / 1000;
                            player.sendMessage("  TX " + d.id() + ": "
                                    + TransactionBuilder.formatDLT(d.amount()) + " DLT ("
                                    + ageSeconds + "s ago)");
                        }
                        long total = pending.stream().mapToLong(BalanceCache.PendingDebit::amount).sum();
                        player.sendMessage("Total locked: " + TransactionBuilder.formatDLT(total) + " DLT");
                    }
                }
            }
            case "import" -> {
                if (!player.hasPermission("dilithium.import")) {
                    player.sendMessage("You don't have permission to import keys.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("Usage: /dilithium import <hex-private-key>");
                    return true;
                }
                String hexKey = args[1];
                try {
                    String newAddress = walletManager.importWallet(playerId, hexKey);
                    player.sendMessage("Wallet imported! New address:");
                    player.sendClickableMessage(newAddress, newAddress);
                    balanceCache.syncAddress(newAddress);
                    long bal = balanceCache.getBalance(newAddress);
                    player.sendMessage("Balance: " + TransactionBuilder.formatDLT(bal) + " DLT");
                } catch (Exception e) {
                    player.sendMessage("Failed to import key: " + e.getMessage());
                }
            }
            case "export" -> {
                if (!player.hasPermission("dilithium.export")) {
                    player.sendMessage("You don't have permission to export keys.");
                    return true;
                }
                WalletData wallet = walletManager.getWallet(playerId);
                if (wallet == null) {
                    player.sendMessage("You don't have a wallet yet. Rejoin the server to create one.");
                    return true;
                }

                String address = wallet.address();
                byte[] rawPubKey = DilithiumCrypto.getRawPublicKey(wallet.privateKeyBytes());
                String pubKeyHex = DilithiumCrypto.bytesToHex(rawPubKey);
                String privKeyHex = DilithiumCrypto.bytesToHex(wallet.privateKeyBytes());

                player.sendMessage("=== Wallet Export ===");

                // Address — gold, click to copy full address
                player.sendRichMessage("Address: " + address, address,
                        "Click to copy address", 0xFFAA00);

                // Public key — aqua, truncated display, click copies full hex
                String pubTruncated = pubKeyHex.substring(0, 32) + "...";
                player.sendRichMessage("Public Key: " + pubTruncated, pubKeyHex,
                        "Click to copy full public key", 0x55FFFF);

                // Private key — red, truncated display, click copies full PKCS#8 hex
                String privTruncated = privKeyHex.substring(0, 32) + "...";
                player.sendRichMessage("Private Key: " + privTruncated, privKeyHex,
                        "Click to copy full private key (SENSITIVE!)", 0xFF5555);

                player.sendMessage("");
                player.sendRichMessage("WARNING: Your private key controls your wallet. Never share it!",
                        "", "This is a security warning", 0xFF5555);

                // File export if requested
                if (args.length >= 2 && args[1].equalsIgnoreCase("file")) {
                    try {
                        Path exportDir = dataDir.resolve("exports");
                        Files.createDirectories(exportDir);
                        Path exportFile = exportDir.resolve(player.name() + ".wallet.json");
                        String json = "{\n"
                                + "  \"address\": \"" + address + "\",\n"
                                + "  \"publicKey\": \"" + pubKeyHex + "\",\n"
                                + "  \"privateKey\": \"" + privKeyHex + "\",\n"
                                + "  \"exportedAt\": \"" + Instant.now() + "\",\n"
                                + "  \"player\": \"" + player.name() + "\"\n"
                                + "}";
                        Files.writeString(exportFile, json);
                        player.sendMessage("Wallet exported to: " + exportFile.toAbsolutePath());
                    } catch (Exception e) {
                        player.sendMessage("Failed to export wallet file: " + e.getMessage());
                    }
                }
            }
            case "book" -> {
                long now = System.currentTimeMillis();
                Long lastUsed = bookCooldowns.get(playerId);
                if (lastUsed != null) {
                    long elapsed = now - lastUsed;
                    if (elapsed < BOOK_COOLDOWN_MS) {
                        long remaining = BOOK_COOLDOWN_MS - elapsed;
                        long hours = remaining / (60 * 60 * 1000L);
                        long minutes = (remaining % (60 * 60 * 1000L)) / (60 * 1000L);
                        if (hours > 0) {
                            player.sendMessage("You can get another book in " + hours + "h " + minutes + "m.");
                        } else {
                            player.sendMessage("You can get another book in " + minutes + " minutes.");
                        }
                        return true;
                    }
                }
                if (BookHelper.giveBook(player)) {
                    bookCooldowns.put(playerId, now);
                    player.sendMessage("You received the Dilithium guide book!");
                } else {
                    player.sendMessage("Could not give book — is your inventory full?");
                }
            }
            default -> sendHelp(player);
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("=== Dilithium Economy ===");
        player.sendMessage("This server uses real Dilithium (DLT) cryptocurrency.");
        player.sendMessage("Your balance is on the blockchain — real, verifiable, yours.");
        player.sendMessage("");
        player.sendMessage("  /dilithium address     - Your wallet address");
        player.sendMessage("  /dilithium key         - Your public key");
        player.sendMessage("  /dilithium balance     - Check your DLT balance");
        player.sendMessage("  /dilithium pending     - View pending transactions");
        player.sendMessage("  /dilithium import <key> - Import a private key");
        player.sendMessage("  /dilithium export      - Export wallet keys (click to copy)");
        player.sendMessage("  /dilithium export file - Export keys + save JSON to server");
        player.sendMessage("  /dilithium book        - Get the Dilithium guide book");
        player.sendMessage("  /pay <player> <amount>  - Send DLT to a player");
        player.sendMessage("  /bankreserve           - Server reserve wallet info");
        player.sendMessage("");
        player.sendMessage("Network: dilithium-mainnet | Fee: 0.0001 DLT | Block time: ~60s");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return List.of("help", "address", "key", "balance", "pending", "import", "export", "book").stream()
                    .filter(s -> s.startsWith(partial))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("export")) {
            String partial = args[1].toLowerCase();
            return List.of("file").stream()
                    .filter(s -> s.startsWith(partial))
                    .toList();
        }
        return List.of();
    }
}

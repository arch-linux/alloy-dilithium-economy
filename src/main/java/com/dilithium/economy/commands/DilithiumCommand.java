package com.dilithium.economy.commands;

import com.dilithium.economy.blockchain.TransactionBuilder;
import com.dilithium.economy.crypto.DilithiumCrypto;
import com.dilithium.economy.crypto.WalletData;
import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.command.Command;
import net.alloymc.api.command.CommandSender;
import net.alloymc.api.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * /dilithium command — wallet management hub.
 * Subcommands: help, address, key, balance, import
 */
public final class DilithiumCommand extends Command {

    private final WalletManager walletManager;
    private final BalanceCache balanceCache;

    public DilithiumCommand(WalletManager walletManager, BalanceCache balanceCache) {
        super("dilithium", "Dilithium wallet commands", "dilithium.wallet");
        this.walletManager = walletManager;
        this.balanceCache = balanceCache;
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
                    player.sendMessage("Your wallet address: " + address);
                }
            }
            case "key" -> {
                WalletData wallet = walletManager.getWallet(playerId);
                if (wallet == null) {
                    player.sendMessage("You don't have a wallet yet. Rejoin the server to create one.");
                } else {
                    byte[] rawPubKey = DilithiumCrypto.getRawPublicKey(wallet.privateKeyBytes());
                    player.sendMessage("Your public key: " + DilithiumCrypto.bytesToHex(rawPubKey));
                }
            }
            case "balance" -> {
                WalletData wallet = walletManager.getWallet(playerId);
                if (wallet == null) {
                    player.sendMessage("You don't have a wallet yet. Rejoin the server to create one.");
                } else {
                    long baseUnits = balanceCache.getBalance(wallet.address());
                    player.sendMessage("Balance: " + TransactionBuilder.formatDLT(baseUnits) + " DLT");
                    if (!balanceCache.isNodeOnline()) {
                        player.sendMessage("(Warning: blockchain node is offline, balance may be stale)");
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
                    player.sendMessage("Wallet imported! New address: " + newAddress);
                    // Sync the new address immediately
                    balanceCache.syncAddress(newAddress);
                    long bal = balanceCache.getBalance(newAddress);
                    player.sendMessage("Balance: " + TransactionBuilder.formatDLT(bal) + " DLT");
                } catch (Exception e) {
                    player.sendMessage("Failed to import key: " + e.getMessage());
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
        player.sendMessage("  /dilithium address   - Your wallet address");
        player.sendMessage("  /dilithium key       - Your public key");
        player.sendMessage("  /dilithium balance   - Check your DLT balance");
        player.sendMessage("  /dilithium import <key> - Import a private key");
        player.sendMessage("  /pay <player> <amount>  - Send DLT to a player");
        player.sendMessage("  /bankreserve         - Server reserve wallet info");
        player.sendMessage("");
        player.sendMessage("Network: dilithium-mainnet | Fee: 0.0001 DLT | Block time: ~60s");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return List.of("help", "address", "key", "balance", "import").stream()
                    .filter(s -> s.startsWith(partial))
                    .toList();
        }
        return List.of();
    }
}

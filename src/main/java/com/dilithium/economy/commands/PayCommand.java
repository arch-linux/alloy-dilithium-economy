package com.dilithium.economy.commands;

import com.dilithium.economy.blockchain.BlockchainClient;
import com.dilithium.economy.blockchain.TransactionBuilder;
import com.dilithium.economy.crypto.WalletData;
import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.AlloyAPI;
import net.alloymc.api.command.Command;
import net.alloymc.api.command.CommandSender;
import net.alloymc.api.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /pay <player> <amount> — Blockchain transfer between players.
 */
public final class PayCommand extends Command {

    private final WalletManager walletManager;
    private final BalanceCache balanceCache;
    private final BlockchainClient client;
    private final String networkName;
    private final long defaultFee;

    public PayCommand(WalletManager walletManager, BalanceCache balanceCache,
                      BlockchainClient client, String networkName, long defaultFee) {
        super("pay", "Send DLT to another player", "dilithium.pay");
        this.walletManager = walletManager;
        this.balanceCache = balanceCache;
        this.client = client;
        this.networkName = networkName;
        this.defaultFee = defaultFee;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.isPlayer()) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage("Usage: /pay <player> <amount>");
            return true;
        }

        String targetName = args[0];
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("Invalid amount: " + args[1]);
            return true;
        }

        if (amount <= 0) {
            player.sendMessage("Amount must be positive.");
            return true;
        }

        // Find target player
        Optional<? extends Player> targetOpt = AlloyAPI.server().player(targetName);
        if (targetOpt.isEmpty()) {
            player.sendMessage("Player not found: " + targetName);
            return true;
        }

        Player target = targetOpt.get();
        if (target.uniqueId().equals(player.uniqueId())) {
            player.sendMessage("You can't pay yourself.");
            return true;
        }

        UUID senderId = player.uniqueId();
        UUID receiverId = target.uniqueId();

        WalletData senderWallet = walletManager.getWallet(senderId);
        WalletData receiverWallet = walletManager.getWallet(receiverId);

        if (senderWallet == null) {
            player.sendMessage("You don't have a wallet. Rejoin the server to create one.");
            return true;
        }
        if (receiverWallet == null) {
            player.sendMessage(target.displayName() + " doesn't have a wallet yet.");
            return true;
        }

        long baseUnits = TransactionBuilder.toBaseUnits(amount);
        long totalCost = baseUnits + defaultFee;
        long senderBalance = balanceCache.getBalance(senderWallet.address());

        if (senderBalance < totalCost) {
            player.sendMessage("Insufficient balance. You have " + TransactionBuilder.formatDLT(senderBalance)
                    + " DLT (need " + TransactionBuilder.formatDLT(totalCost) + " DLT including fee).");
            return true;
        }

        // Optimistically update caches
        balanceCache.recordPendingDebit(senderWallet.address(), totalCost);
        balanceCache.recordPendingCredit(receiverWallet.address(), baseUnits);

        // Submit TX async
        String formattedAmount = TransactionBuilder.formatDLT(baseUnits);
        Thread.ofVirtual().name("DilithiumEconomy-Pay").start(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    senderWallet, receiverWallet.address(), baseUnits, defaultFee, networkName, client);
            if (ok) {
                player.sendMessage("Sent " + formattedAmount + " DLT to " + target.displayName()
                        + "! Transaction submitted to blockchain. Mining in ~60s.");
                target.sendMessage("Received " + formattedAmount + " DLT from " + player.displayName()
                        + "! Transaction is being mined.");
            } else {
                player.sendMessage("Transaction failed! The blockchain node may be unreachable.");
            }
        });

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return AlloyAPI.server().onlinePlayers().stream()
                    .map(Player::name)
                    .filter(n -> n.toLowerCase().startsWith(partial))
                    .toList();
        }
        return List.of();
    }
}

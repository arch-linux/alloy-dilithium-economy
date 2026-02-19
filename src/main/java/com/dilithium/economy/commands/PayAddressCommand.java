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
import java.util.UUID;

/**
 * /payaddress &lt;address&gt; &lt;amount&gt; — Send DLT to any blockchain address.
 *
 * <p>Unlike /pay which resolves player names to addresses, this command takes
 * a raw blockchain address directly. Useful for paying external wallets,
 * services, or addresses not associated with an online player.
 */
public final class PayAddressCommand extends Command {

    private final WalletManager walletManager;
    private final BalanceCache balanceCache;
    private final BlockchainClient client;
    private final String networkName;
    private final long defaultFee;

    public PayAddressCommand(WalletManager walletManager, BalanceCache balanceCache,
                             BlockchainClient client, String networkName, long defaultFee) {
        super("payaddress", "Send DLT to a blockchain address", "dilithium.pay",
                List.of("sendto", "payaddr"));
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
            player.sendMessage("Usage: /payaddress <address> <amount>");
            player.sendMessage("Sends DLT directly to a blockchain address.");
            return true;
        }

        String targetAddress = args[0].toLowerCase();
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

        // Basic address validation — Dilithium addresses are 40-char hex (SHA-1 of public key)
        if (!targetAddress.matches("^[0-9a-f]{40}$")) {
            player.sendMessage("Invalid address format. Expected 40-character hex address.");
            return true;
        }

        UUID senderId = player.uniqueId();
        WalletData senderWallet = walletManager.getWallet(senderId);
        if (senderWallet == null) {
            player.sendMessage("You don't have a wallet. Rejoin the server to create one.");
            return true;
        }

        if (senderWallet.address().equals(targetAddress)) {
            player.sendMessage("You can't send to your own address.");
            return true;
        }

        long baseUnits = TransactionBuilder.toBaseUnits(amount);
        long totalCost = baseUnits + defaultFee;

        // Atomically check balance and reserve funds
        BalanceCache.DebitResult debitResult = balanceCache.tryDebit(senderWallet.address(), totalCost);
        if (!debitResult.success()) {
            String sym = AlloyAPI.economy().currencySymbol();
            player.sendMessage("Insufficient balance. You have " + sym
                    + TransactionBuilder.formatDLT(debitResult.remainingBalance())
                    + " (need " + sym + TransactionBuilder.formatDLT(totalCost)
                    + " including " + sym + TransactionBuilder.formatDLT(defaultFee) + " fee).");
            long pendingTotal = balanceCache.getTotalPendingDebits(senderWallet.address());
            if (pendingTotal > 0) {
                player.sendMessage("(" + sym + TransactionBuilder.formatDLT(pendingTotal)
                        + " locked in pending transactions)");
            }
            return true;
        }

        String txId = debitResult.txId();
        String formattedAmount = TransactionBuilder.formatDLT(baseUnits);
        String sym = AlloyAPI.economy().currencySymbol();
        String shortAddr = targetAddress.substring(0, 8) + "..." + targetAddress.substring(32);

        // Check if this address belongs to a known player
        UUID targetPlayerId = walletManager.getPlayerByAddress(targetAddress);
        String targetLabel = targetPlayerId != null
                ? AlloyAPI.server().player(targetPlayerId).map(Player::displayName).orElse(shortAddr)
                : shortAddr;

        // Submit TX async
        Thread.ofVirtual().name("DilithiumEconomy-PayAddr").start(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    senderWallet, targetAddress, baseUnits, defaultFee, networkName, client);
            if (ok) {
                player.sendMessage("Sent " + sym + formattedAmount + " to " + targetLabel
                        + "! TX submitted to blockchain.");

                // Notify target if they're an online player
                if (targetPlayerId != null) {
                    AlloyAPI.server().player(targetPlayerId).ifPresent(t ->
                            t.sendMessage("Received " + sym + formattedAmount
                                    + " from " + player.displayName()
                                    + "! Transaction is being mined."));
                }
            } else {
                balanceCache.removePendingDebit(txId);
                player.sendMessage("Transaction failed! Your balance has been restored. "
                        + "The blockchain node may be unreachable.");
            }
        });

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return List.of();
    }
}

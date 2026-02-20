package com.dilithium.economy.commands;

import com.dilithium.economy.blockchain.BlockchainClient;
import com.dilithium.economy.blockchain.TransactionBuilder;
import com.dilithium.economy.crypto.WalletData;
import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.wallet.ReserveWallet;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.AlloyAPI;
import net.alloymc.api.command.Command;
import net.alloymc.api.command.CommandSender;
import net.alloymc.api.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /pay &lt;player|reserve&gt; &lt;amount&gt; — Blockchain transfer.
 *
 * <p>Targets:
 * <ul>
 *   <li>{@code /pay <player> <amount>} — Send DLT to another player's wallet</li>
 *   <li>{@code /pay reserve <amount>} — Send DLT to the server's reserve wallet</li>
 * </ul>
 *
 * <p>Uses atomic {@link BalanceCache#tryDebit} to prevent double-spending.
 * If the blockchain node rejects the TX, the pending debit is rolled back immediately.
 */
public final class PayCommand extends Command {

    private final WalletManager walletManager;
    private final ReserveWallet reserveWallet;
    private final BalanceCache balanceCache;
    private final BlockchainClient client;
    private final String networkName;
    private final long defaultFee;

    public PayCommand(WalletManager walletManager, ReserveWallet reserveWallet,
                      BalanceCache balanceCache, BlockchainClient client,
                      String networkName, long defaultFee) {
        super("pay", "Send DLT to a player or the server reserve", "dilithium.pay");
        this.walletManager = walletManager;
        this.reserveWallet = reserveWallet;
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
            player.sendMessage("Usage: /pay <player|reserve> <amount>");
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

        UUID senderId = player.uniqueId();
        WalletData senderWallet = walletManager.getWallet(senderId);
        if (senderWallet == null) {
            player.sendMessage("You don't have a wallet. Rejoin the server to create one.");
            return true;
        }

        // Determine target address and display name
        String targetAddress;
        String targetDisplayName;

        if (targetName.equalsIgnoreCase("reserve") || targetName.equalsIgnoreCase("server")) {
            targetAddress = reserveWallet.address();
            targetDisplayName = "Server Reserve";
        } else {
            Optional<? extends Player> targetOpt = AlloyAPI.server().player(targetName);
            if (targetOpt.isEmpty()) {
                player.sendMessage("Player not found: " + targetName
                        + ". Use /payaddress to send to a raw address.");
                return true;
            }

            Player target = targetOpt.get();
            if (target.uniqueId().equals(player.uniqueId())) {
                player.sendMessage("You can't pay yourself.");
                return true;
            }

            WalletData receiverWallet = walletManager.getWallet(target.uniqueId());
            if (receiverWallet == null) {
                player.sendMessage(target.displayName() + " doesn't have a wallet yet.");
                return true;
            }

            targetAddress = receiverWallet.address();
            targetDisplayName = target.displayName();

            // Optimistically credit receiver for immediate UX
            long creditUnits = TransactionBuilder.toBaseUnits(amount);
            balanceCache.addPendingCredit(targetAddress, creditUnits);
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
        String finalTargetAddress = targetAddress;
        String finalTargetDisplayName = targetDisplayName;

        // Submit TX async
        Thread.ofVirtual().name("DilithiumEconomy-Pay").start(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    senderWallet, finalTargetAddress, baseUnits, defaultFee, networkName, client);
            if (ok) {
                player.sendMessage("Sent " + sym + formattedAmount + " to " + finalTargetDisplayName
                        + "! TX submitted to blockchain.");

                // Notify target if it's an online player (not reserve)
                if (!finalTargetAddress.equals(reserveWallet.address())) {
                    // Record as expected so sync loop won't double-notify
                    balanceCache.recordExpectedIncoming(finalTargetAddress, senderWallet.address(), baseUnits);

                    Optional<? extends Player> targetOpt = AlloyAPI.server().player(finalTargetDisplayName);
                    targetOpt.ifPresent(t -> t.sendMessage("Received " + sym + formattedAmount
                            + " from " + player.displayName() + "! Transaction is being mined."));
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
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            suggestions.add("reserve");
            AlloyAPI.server().onlinePlayers().stream()
                    .map(Player::name)
                    .forEach(suggestions::add);
            return suggestions.stream()
                    .filter(n -> n.toLowerCase().startsWith(partial))
                    .toList();
        }
        return List.of();
    }
}

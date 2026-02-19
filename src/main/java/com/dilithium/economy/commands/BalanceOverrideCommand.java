package com.dilithium.economy.commands;

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
 * Overrides the built-in /balance command with blockchain-aware balance display.
 * Shows on-chain balance, pending outgoing transactions, and node status.
 */
public final class BalanceOverrideCommand extends Command {

    private final WalletManager walletManager;
    private final BalanceCache balanceCache;

    public BalanceOverrideCommand(WalletManager walletManager, BalanceCache balanceCache) {
        super("balance", "Check your Dilithium balance", "alloy.command.balance",
                List.of("bal", "money"));
        this.walletManager = walletManager;
        this.balanceCache = balanceCache;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.isPlayer()) {
                sender.sendMessage("\u00a7cConsole must specify a player: /balance <player>");
                return true;
            }
            showBalance((Player) sender, (Player) sender);
        } else {
            if (sender.isPlayer() && !sender.hasPermission("alloy.command.setmoney")) {
                sender.sendMessage("\u00a7cYou can only check your own balance.");
                return true;
            }
            String targetName = args[0];
            var target = AlloyAPI.server().player(targetName);
            if (target.isEmpty()) {
                sender.sendMessage("\u00a7cPlayer not found: " + targetName);
                return true;
            }
            Player targetPlayer = target.get();
            if (sender.isPlayer()) {
                showBalanceFor((Player) sender, targetPlayer);
            } else {
                showBalanceForConsole(sender, targetPlayer);
            }
        }
        return true;
    }

    private void showBalance(Player sender, Player target) {
        UUID playerId = target.uniqueId();
        WalletData wallet = walletManager.getWallet(playerId);
        if (wallet == null) {
            sender.sendMessage("\u00a7cNo wallet found. Rejoin the server to create one.");
            return;
        }

        String sym = AlloyAPI.economy().currencySymbol();
        long effective = balanceCache.getBalance(wallet.address());
        long pendingTotal = balanceCache.getTotalPendingDebits(wallet.address());

        sender.sendMessage("\u00a7aBalance: \u00a7f" + sym + TransactionBuilder.formatDLT(effective));
        if (pendingTotal > 0) {
            sender.sendMessage("\u00a77  Pending outgoing: " + sym
                    + TransactionBuilder.formatDLT(pendingTotal)
                    + " (locked until mined)");
        }
        if (!balanceCache.isNodeOnline()) {
            sender.sendMessage("\u00a7e  Warning: blockchain node offline, balance may be stale");
        }
    }

    private void showBalanceFor(Player sender, Player target) {
        UUID playerId = target.uniqueId();
        WalletData wallet = walletManager.getWallet(playerId);
        if (wallet == null) {
            sender.sendMessage("\u00a7c" + target.name() + " does not have a wallet.");
            return;
        }

        String sym = AlloyAPI.economy().currencySymbol();
        long effective = balanceCache.getBalance(wallet.address());
        sender.sendMessage("\u00a7a" + target.name() + "'s balance: \u00a7f" + sym
                + TransactionBuilder.formatDLT(effective));
    }

    private void showBalanceForConsole(CommandSender sender, Player target) {
        UUID playerId = target.uniqueId();
        WalletData wallet = walletManager.getWallet(playerId);
        if (wallet == null) {
            sender.sendMessage(target.name() + " does not have a wallet.");
            return;
        }

        long effective = balanceCache.getBalance(wallet.address());
        sender.sendMessage(target.name() + "'s balance: "
                + AlloyAPI.economy().currencySymbol() + TransactionBuilder.formatDLT(effective));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission("alloy.command.setmoney")) {
            String prefix = args[0].toLowerCase();
            return AlloyAPI.server().onlinePlayers().stream()
                    .map(Player::name)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}

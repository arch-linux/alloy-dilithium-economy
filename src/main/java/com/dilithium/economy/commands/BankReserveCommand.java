package com.dilithium.economy.commands;

import com.dilithium.economy.blockchain.TransactionBuilder;
import com.dilithium.economy.economy.BalanceCache;
import com.dilithium.economy.wallet.ReserveWallet;
import net.alloymc.api.command.Command;
import net.alloymc.api.command.CommandSender;

/**
 * /bankreserve — Shows the server reserve wallet address and balance.
 */
public final class BankReserveCommand extends Command {

    private final ReserveWallet reserveWallet;
    private final BalanceCache balanceCache;

    public BankReserveCommand(ReserveWallet reserveWallet, BalanceCache balanceCache) {
        super("bankreserve", "Show server reserve wallet info", "dilithium.reserve");
        this.reserveWallet = reserveWallet;
        this.balanceCache = balanceCache;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String address = reserveWallet.address();
        long balance = balanceCache.getBalance(address);

        sender.sendMessage("=== Server Reserve Wallet ===");
        sender.sendMessage("Address: " + address);
        sender.sendMessage("Balance: " + TransactionBuilder.formatDLT(balance) + " DLT");

        if (!balanceCache.isNodeOnline()) {
            sender.sendMessage("(Warning: blockchain node is offline, balance may be stale)");
        }

        return true;
    }
}

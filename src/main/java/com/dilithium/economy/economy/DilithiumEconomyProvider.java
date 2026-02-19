package com.dilithium.economy.economy;

import com.dilithium.economy.blockchain.BlockchainClient;
import com.dilithium.economy.blockchain.TransactionBuilder;
import com.dilithium.economy.crypto.WalletData;
import com.dilithium.economy.wallet.ReserveWallet;
import com.dilithium.economy.wallet.WalletManager;
import net.alloymc.api.economy.EconomyProvider;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EconomyProvider backed by the Dilithium blockchain.
 *
 * Optimistic local cache: synchronous methods return immediately using cached balances.
 * Actual blockchain transactions are submitted asynchronously.
 * Periodic sync (via BalanceCache) reconciles cache with on-chain truth.
 */
public final class DilithiumEconomyProvider implements EconomyProvider {

    private final WalletManager walletManager;
    private final ReserveWallet reserveWallet;
    private final BlockchainClient client;
    private final BalanceCache balanceCache;
    private final String networkName;
    private final long defaultFee;

    private final ExecutorService txExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "DilithiumEconomy-TX");
        t.setDaemon(true);
        return t;
    });

    public DilithiumEconomyProvider(WalletManager walletManager, ReserveWallet reserveWallet,
                                    BlockchainClient client, BalanceCache balanceCache,
                                    String networkName, long defaultFee) {
        this.walletManager = walletManager;
        this.reserveWallet = reserveWallet;
        this.client = client;
        this.balanceCache = balanceCache;
        this.networkName = networkName;
        this.defaultFee = defaultFee;
    }

    @Override
    public double getBalance(UUID playerId) {
        WalletData wallet = walletManager.getWallet(playerId);
        if (wallet == null) return 0.0;
        long baseUnits = balanceCache.getBalance(wallet.address());
        return TransactionBuilder.toDLT(baseUnits);
    }

    @Override
    public void setBalance(UUID playerId, double amount) {
        double current = getBalance(playerId);
        double delta = amount - current;
        if (delta > 0) {
            deposit(playerId, delta);
        } else if (delta < 0) {
            withdraw(playerId, -delta);
        }
    }

    @Override
    public void deposit(UUID playerId, double amount) {
        WalletData playerWallet = walletManager.getWallet(playerId);
        if (playerWallet == null) {
            System.err.println("[DilithiumEconomy] Cannot deposit: no wallet for " + playerId);
            return;
        }

        long baseUnits = TransactionBuilder.toBaseUnits(amount);
        if (baseUnits <= 0) return;

        // Optimistically credit the player's cache
        balanceCache.recordPendingCredit(playerWallet.address(), baseUnits);

        // Submit TX async: reserve → player
        txExecutor.submit(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    reserveWallet.wallet(), playerWallet.address(), baseUnits, defaultFee, networkName, client);
            if (!ok) {
                System.err.println("[DilithiumEconomy] Deposit TX failed for " + playerId);
            }
        });
    }

    @Override
    public boolean withdraw(UUID playerId, double amount) {
        WalletData playerWallet = walletManager.getWallet(playerId);
        if (playerWallet == null) return false;

        long baseUnits = TransactionBuilder.toBaseUnits(amount);
        if (baseUnits <= 0) return false;

        long totalCost = baseUnits + defaultFee;
        long currentBalance = balanceCache.getBalance(playerWallet.address());
        if (currentBalance < totalCost) return false;

        // Optimistically debit the player's cache
        balanceCache.recordPendingDebit(playerWallet.address(), totalCost);

        // Submit TX async: player → reserve
        txExecutor.submit(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    playerWallet, reserveWallet.address(), baseUnits, defaultFee, networkName, client);
            if (!ok) {
                System.err.println("[DilithiumEconomy] Withdraw TX failed for " + playerId);
            }
        });

        return true;
    }

    @Override
    public boolean has(UUID playerId, double amount) {
        return getBalance(playerId) >= amount;
    }

    @Override
    public boolean transfer(UUID from, UUID to, double amount) {
        WalletData senderWallet = walletManager.getWallet(from);
        WalletData receiverWallet = walletManager.getWallet(to);
        if (senderWallet == null || receiverWallet == null) return false;

        long baseUnits = TransactionBuilder.toBaseUnits(amount);
        if (baseUnits <= 0) return false;

        long totalCost = baseUnits + defaultFee;
        long senderBalance = balanceCache.getBalance(senderWallet.address());
        if (senderBalance < totalCost) return false;

        // Optimistically update caches
        balanceCache.recordPendingDebit(senderWallet.address(), totalCost);
        balanceCache.recordPendingCredit(receiverWallet.address(), baseUnits);

        // Submit TX async: sender → receiver
        txExecutor.submit(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    senderWallet, receiverWallet.address(), baseUnits, defaultFee, networkName, client);
            if (!ok) {
                System.err.println("[DilithiumEconomy] Transfer TX failed: " + from + " -> " + to);
            }
        });

        return true;
    }

    @Override
    public void onEnable() {
        System.out.println("[DilithiumEconomy] Dilithium economy provider enabled");
    }

    @Override
    public void onDisable() {
        txExecutor.shutdownNow();
        balanceCache.shutdown();
        System.out.println("[DilithiumEconomy] Dilithium economy provider disabled");
    }
}

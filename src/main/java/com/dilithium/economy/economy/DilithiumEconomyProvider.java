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
 * <p>Uses {@link BalanceCache} for pending transaction tracking to prevent
 * double-spending. All outgoing TXs go through {@link BalanceCache#tryDebit}
 * which atomically checks balance and reserves funds. If the node rejects
 * the TX, the pending debit is rolled back immediately.
 *
 * <p>Deposits use optimistic credits — the player sees the balance immediately
 * and it reconciles on the next sync cycle.
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
        // Cannot directly set a blockchain balance. Use deposit/withdraw for adjustments.
        System.err.println("[DilithiumEconomy] setBalance() is not supported on blockchain economy. "
                + "Use deposit/withdraw instead. Player: " + playerId + " requested: " + amount);
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

        // Optimistically credit the player's cache for immediate UX
        balanceCache.addPendingCredit(playerWallet.address(), baseUnits);

        // Submit TX async: reserve → player
        txExecutor.submit(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    reserveWallet.wallet(), playerWallet.address(), baseUnits, defaultFee, networkName, client);
            if (!ok) {
                System.err.println("[DilithiumEconomy] Deposit TX failed for " + playerId
                        + " (" + TransactionBuilder.formatDLT(baseUnits) + " DLT). "
                        + "Credit will be corrected on next sync.");
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

        // Atomically check balance and reserve funds
        BalanceCache.DebitResult result = balanceCache.tryDebit(playerWallet.address(), totalCost);
        if (!result.success()) return false;

        String txId = result.txId();

        // Submit TX async: player → reserve
        txExecutor.submit(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    playerWallet, reserveWallet.address(), baseUnits, defaultFee, networkName, client);
            if (!ok) {
                // TX rejected — immediately rollback the pending debit
                balanceCache.removePendingDebit(txId);
                System.err.println("[DilithiumEconomy] Withdraw TX failed for " + playerId
                        + " (" + TransactionBuilder.formatDLT(baseUnits) + " DLT). Pending debit rolled back.");
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

        // Atomically check sender balance and reserve funds
        BalanceCache.DebitResult result = balanceCache.tryDebit(senderWallet.address(), totalCost);
        if (!result.success()) return false;

        String txId = result.txId();

        // Optimistically credit receiver
        balanceCache.addPendingCredit(receiverWallet.address(), baseUnits);

        // Submit TX async: sender → receiver
        txExecutor.submit(() -> {
            boolean ok = TransactionBuilder.buildAndSubmit(
                    senderWallet, receiverWallet.address(), baseUnits, defaultFee, networkName, client);
            if (!ok) {
                // TX rejected — rollback sender's pending debit
                balanceCache.removePendingDebit(txId);
                System.err.println("[DilithiumEconomy] Transfer TX failed: " + from + " -> " + to
                        + " (" + TransactionBuilder.formatDLT(baseUnits) + " DLT). Pending debit rolled back.");
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

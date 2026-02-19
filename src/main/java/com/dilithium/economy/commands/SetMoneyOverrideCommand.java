package com.dilithium.economy.commands;

import net.alloymc.api.command.Command;
import net.alloymc.api.command.CommandSender;

import java.util.List;

/**
 * Overrides the built-in /setmoney command when Dilithium Economy is active.
 * Balances on a blockchain cannot be set arbitrarily — they are determined
 * by on-chain transaction history.
 */
public final class SetMoneyOverrideCommand extends Command {

    public SetMoneyOverrideCommand() {
        super("setmoney", "Set a player's balance (disabled with blockchain economy)",
                "alloy.command.setmoney", List.of("setbalance", "setbal"));
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        sender.sendMessage("\u00a7cThis command is disabled while Dilithium Economy is active.");
        sender.sendMessage("\u00a7cBlockchain balances are determined by on-chain transactions.");
        sender.sendMessage("\u00a7cTo fund a player, use: \u00a7f/pay <player> <amount>");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return List.of();
    }
}

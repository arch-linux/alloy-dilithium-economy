package com.dilithium.economy.util;

import net.alloymc.api.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates and gives a Dilithium guide book to a player using MC 1.21.11 reflection.
 *
 * <pre>
 * Obfuscated mappings used:
 *   Items (dlx):                   WRITTEN_BOOK = wl (static field)
 *   ItemStack (dlt):               constructor(ItemLike, int), set(DataComponentType, Object) -> b(kh, Object)
 *   ItemLike (dwn):                interface implemented by Item
 *   Component (yh):                literal(String) -> b(String)
 *   Filterable (axx):              passThrough(Object) -> a(Object)
 *   WrittenBookContent (dpl):      constructor(Filterable, String, int, List, boolean)
 *   DataComponents (ki):           WRITTEN_BOOK_CONTENT = ac (static field)
 *   DataComponentType (kh):        parameter type for ItemStack.set
 *   Inventory (ddl):               add(ItemStack) -> g(dlt)
 *   ServerPlayer (axg):            getInventory() -> gK()
 * </pre>
 */
public final class BookHelper {

    private static final String BOOK_TITLE = "Dilithium: A Guide";
    private static final String BOOK_AUTHOR = "Dilithium Network";

    private static final String[] PAGES = {
        // Page 1: What is Dilithium?
        "What is Dilithium?\n\n"
            + "Dilithium (DLT) is a real cryptocurrency on a quantum-resistant blockchain.\n\n"
            + "Your in-game balance isn't fake money \u2014 it's real DLT on a live network, "
            + "secured by post-quantum cryptography.",

        // Page 2: How It Works
        "How It Works\n\n"
            + "When you first join, a wallet is automatically created for you.\n\n"
            + "Your balance lives on the actual Dilithium blockchain, not in a server file.\n\n"
            + "Every transaction is signed with Dilithium3 quantum-resistant signatures.",

        // Page 3: Transactions
        "Transactions\n\n"
            + "Use /pay <player> <amount> to send DLT to another player.\n\n"
            + "Your transaction is signed with your private key and submitted to the blockchain.\n\n"
            + "Confirmation takes about 60 seconds as miners verify the block.",

        // Page 4: Transaction Fees
        "Transaction Fees\n\n"
            + "Each transaction costs 0.0001 DLT.\n\n"
            + "This small fee goes to miners who secure the network by validating blocks.\n\n"
            + "It prevents spam and keeps the blockchain running efficiently.",

        // Page 5: Your Wallet
        "Your Wallet\n\n"
            + "/dilithium address\n"
            + "  View your wallet address\n\n"
            + "/dilithium balance\n"
            + "  Check your DLT balance\n\n"
            + "/dilithium key\n"
            + "  View your public key\n\n"
            + "/dilithium export\n"
            + "  Export wallet keys\n\n"
            + "/dilithium book\n"
            + "  Get this guide again",

        // Page 6: Learn More
        "Learn More\n\n"
            + "Visit dilithiumcoin.com to learn about the Dilithium network.\n\n"
            + "You can run your own node and start mining DLT yourself.\n\n"
            + "The network is secured by CRYSTALS-Dilithium, a NIST-selected "
            + "post-quantum signature algorithm."
    };

    private BookHelper() {}

    /**
     * Creates a written book with Dilithium guide content and adds it to the player's inventory.
     *
     * @param player the Alloy API player to give the book to
     * @return true if the book was successfully added, false if inventory was full or reflection failed
     */
    public static boolean giveBook(Player player) {
        try {
            // 1. Get MC ServerPlayer handle from the Alloy Player wrapper
            Method handleMethod = player.getClass().getMethod("handle");
            Object serverPlayer = handleMethod.invoke(player);
            ClassLoader cl = serverPlayer.getClass().getClassLoader();

            // 2. Get Items.WRITTEN_BOOK item instance
            Class<?> itemsClass = cl.loadClass("dlx");
            Field writtenBookField = itemsClass.getDeclaredField("wl");
            writtenBookField.setAccessible(true);
            Object writtenBookItem = writtenBookField.get(null);

            // 3. Create new ItemStack(ItemLike, 1)
            Class<?> itemStackClass = cl.loadClass("dlt");
            Class<?> itemLikeClass = cl.loadClass("dwn");
            Constructor<?> itemStackCtor = itemStackClass.getConstructor(itemLikeClass, int.class);
            Object itemStack = itemStackCtor.newInstance(writtenBookItem, 1);

            // 4. Find Component.literal(String) -> yh.b(String)
            Class<?> componentClass = cl.loadClass("yh");
            Method literalMethod = null;
            for (Method m : componentClass.getMethods()) {
                if (m.getName().equals("b") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                    literalMethod = m;
                    break;
                }
            }
            if (literalMethod == null) {
                System.err.println("[DilithiumEconomy] Could not find Component.literal method");
                return false;
            }

            // 5. Find Filterable.passThrough(Object) -> axx.a(Object)
            Class<?> filterableClass = cl.loadClass("axx");
            Method passThroughMethod = null;
            for (Method m : filterableClass.getMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Object.class) {
                    passThroughMethod = m;
                    break;
                }
            }
            if (passThroughMethod == null) {
                System.err.println("[DilithiumEconomy] Could not find Filterable.passThrough method");
                return false;
            }

            // 6. Build page list: List<Filterable<Component>>
            List<Object> pages = new ArrayList<>();
            for (String pageText : PAGES) {
                Object component = literalMethod.invoke(null, pageText);
                Object filterable = passThroughMethod.invoke(null, component);
                pages.add(filterable);
            }

            // 7. Build title as Filterable<String> (title is a String, NOT a Component)
            Object titleFilterable = passThroughMethod.invoke(null, BOOK_TITLE);

            // 8. Create WrittenBookContent(title, author, generation, pages, resolved)
            //    dpl(axx, String, int, List, boolean)
            Class<?> contentClass = cl.loadClass("dpl");
            Constructor<?> contentCtor = null;
            for (Constructor<?> ctor : contentClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 5
                        && ctor.getParameterTypes()[0] == filterableClass
                        && ctor.getParameterTypes()[1] == String.class
                        && ctor.getParameterTypes()[2] == int.class
                        && ctor.getParameterTypes()[3] == List.class
                        && ctor.getParameterTypes()[4] == boolean.class) {
                    contentCtor = ctor;
                    break;
                }
            }
            if (contentCtor == null) {
                System.err.println("[DilithiumEconomy] Could not find WrittenBookContent constructor");
                return false;
            }
            Object bookContent = contentCtor.newInstance(titleFilterable, BOOK_AUTHOR, 0, pages, true);

            // 9. Get DataComponents.WRITTEN_BOOK_CONTENT component type
            Class<?> dataComponentsClass = cl.loadClass("ki");
            Field writtenBookContentField = dataComponentsClass.getDeclaredField("ac");
            writtenBookContentField.setAccessible(true);
            Object writtenBookContentType = writtenBookContentField.get(null);

            // 10. Set the component on the ItemStack: itemStack.set(type, content) -> dlt.b(kh, Object)
            Class<?> dctClass = cl.loadClass("kh");
            Method setMethod = null;
            for (Method m : itemStackClass.getMethods()) {
                if (m.getName().equals("b") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == dctClass
                        && m.getParameterTypes()[1] == Object.class) {
                    setMethod = m;
                    break;
                }
            }
            if (setMethod == null) {
                System.err.println("[DilithiumEconomy] Could not find ItemStack.set method");
                return false;
            }
            setMethod.invoke(itemStack, writtenBookContentType, bookContent);

            // 11. Get player inventory: ServerPlayer.getInventory() -> gK()
            Method getInventoryMethod = serverPlayer.getClass().getMethod("gK");
            Object inventory = getInventoryMethod.invoke(serverPlayer);

            // 12. Add book to inventory: Inventory.add(ItemStack) -> ddl.g(dlt)
            Method addMethod = null;
            for (Method m : inventory.getClass().getMethods()) {
                if (m.getName().equals("g") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == itemStackClass) {
                    addMethod = m;
                    break;
                }
            }
            if (addMethod == null) {
                System.err.println("[DilithiumEconomy] Could not find Inventory.add method");
                return false;
            }
            return (boolean) addMethod.invoke(inventory, itemStack);

        } catch (Exception e) {
            System.err.println("[DilithiumEconomy] Failed to give book to " + player.name() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}

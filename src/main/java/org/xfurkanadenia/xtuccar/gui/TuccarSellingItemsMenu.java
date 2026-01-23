package org.xfurkanadenia.xtuccar.gui;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.xfurkanadenia.xtuccar.XTuccar;
import org.xfurkanadenia.xtuccar.file.Locale;
import org.xfurkanadenia.xtuccar.manager.TuccarManager;
import org.xfurkanadenia.xtuccar.model.MarketItem;
import org.xfurkanadenia.xtuccar.model.MarketSellingItem;
import org.xfurkanadenia.xtuccar.model.Validator;
import org.xfurkanadenia.xtuccar.model.cFastInv;
import org.xfurkanadenia.xtuccar.util.Utils;

import java.util.*;

public class TuccarSellingItemsMenu extends cFastInv {
    private int page;
    private String itemId;
    private boolean hasNextPage = false;
    public TuccarSellingItemsMenu(Player p, String itemId, int page) {
        super("tuccarSellingItems", getPlaceholders(itemId, page));
        this.page = page;
        this.itemId = itemId;
        XTuccar plugin = XTuccar.getInstance();
        TuccarManager tuccarManager = plugin.getTuccarManager();
        MarketItem marketItem = tuccarManager.getMarketItem(itemId);
        Locale locale = plugin.getLocale();
        Economy economy = plugin.getVaultIntegration().getEconomy();

        var itemsConfig = getItems().stream()
                .filter(i -> i.getType().equalsIgnoreCase("items"))
                .findFirst()
                .orElse(null);

        if (itemsConfig == null || marketItem == null) return;
        int pageSize = itemsConfig.getSlots().size();
            getItems().forEach(item -> {
                int[] slots = item.getSlots().stream().mapToInt(Integer::intValue).toArray();

                switch (item.getType().toLowerCase()) {
                    case "items" -> {}
                    case String s when s.equalsIgnoreCase("previous_page") -> {
                        setItems(slots, Utils.getFormattedItem(item.getItem(), p, getPlaceholders(marketItem, page)), e -> {
                            if (page > 1) new TuccarSellingItemsMenu(p, itemId, page - 1).open(p);
                        });
                    }

                    case String s when s.equalsIgnoreCase("next_page") -> {
                        setItems(slots, Utils.getFormattedItem(item.getItem(), p, getPlaceholders(marketItem, page)), e -> {
                            if (hasNextPage) new TuccarSellingItemsMenu(p, itemId, page + 1).open(p);
                        });
                    }

                    case "back" -> setItems(slots, Utils.getFormattedItem(item.getItem(), p, getPlaceholders(marketItem, page)),
                            e -> new TuccarCategoryItemsMenu(marketItem.getCategory(), p).open(p));
                    default -> setItems(slots, Utils.getFormattedItem(item.getItem(), p, getPlaceholders(marketItem, page)));
                }
            });
        setSellingItems(p);
    }

    private void setSellingItems(Player p) {
        XTuccar plugin = XTuccar.getInstance();
        TuccarManager tuccarManager = plugin.getTuccarManager();
        Economy economy = plugin.getVaultIntegration().getEconomy();
        MarketItem marketItem = tuccarManager.getMarketItem(itemId);
        Locale locale = plugin.getLocale();
        var itemsConfig = getItems().stream()
                .filter(i -> i.getType().equalsIgnoreCase("items"))
                .findFirst()
                .orElse(null);
        if(itemsConfig == null) return;
        int[] slots = itemsConfig.getSlots().stream().mapToInt(Integer::intValue).toArray();
        int pageSize = itemsConfig.getSlots().size();
        tuccarManager.getItems(itemId, page, pageSize, (items, hasNextPage) -> {
            this.hasNextPage = hasNextPage;
            List<MarketSellingItem> itemsList = new ArrayList<>(items.values());
            itemsConfig.getSlots().forEach(item -> {
                for (int i = 0; i < itemsList.size() && i < slots.length; i++) {
                    MarketSellingItem v = itemsList.get(i);
                    int slot = slots[i];

                    ItemStack itemStack = new ItemStack(marketItem.getItem());
                    ItemMeta itemMeta = itemStack.getItemMeta();
                    itemMeta.setLore(itemsConfig.getItem().getItemMeta().getLore());
                    itemStack.setItemMeta(itemMeta);

                    setItem(slot, Utils.getFormattedItem(itemStack, p, Utils.getSellingItemPlaceholders(v), false), e -> {
                        int amount = switch (e.getClick()) {
                            case LEFT, DOUBLE_CLICK -> 1;
                            case SHIFT_LEFT -> 64;
                            case RIGHT -> -1;
                            default -> 0;
                        };
                        if (amount == 0) return;

                        if (amount == -1) {
                            p.closeInventory();
                            plugin.getDataManager().getPlayerItemBuyChat().put(p, v);
                            locale.sendMessage(p, "player-item-buy-chat");
                            return;
                        }

                        if(!Validator.ValidateStock(v, amount, p)) return;
                        if(!Validator.ValidateHasSpace(p, v.getMarketItem().getItem())) return;
                        if(!Validator.ValidateMoney(p, amount * v.getPrice())) return;


                        new BuyConfirmMenu(v, p, amount).open(p);
                    });
                }
            });
        });
    }

    private static Map<String, String> getPlaceholders(MarketItem marketItem, int page) {
        return getPlaceholders(marketItem.getItemId(), page);
    }
    private static Map<String, String> getPlaceholders(String itemId, int page) {
        TuccarManager tuccarManager = XTuccar.getInstance().getTuccarManager();
        MarketItem marketItem = tuccarManager.getMarketItem(itemId);
        assert marketItem != null;
        Map<String, String> placeholders = new HashMap<>(Utils.getItemPlaceholders(marketItem));
        placeholders.put("page", String.valueOf(page));
        return placeholders;
    }
}
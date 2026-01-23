package org.xfurkanadenia.xtuccar.manager;

import org.bukkit.entity.Player;
import org.xfurkanadenia.xtuccar.model.MarketSellingItem;

import java.util.HashMap;
import java.util.Map;

public class DataManager {
    private final Map<Player, MarketSellingItem> playerItemBuyChat;
    private final Map<Player, MarketSellingItem> playerItemCancelChat;
    public DataManager() {

        playerItemBuyChat = new HashMap<>();
        playerItemCancelChat = new HashMap<>();
    }

    public Map<Player, MarketSellingItem> getPlayerItemBuyChat() {
        return playerItemBuyChat;
    }

    public Map<Player, MarketSellingItem> getPlayerItemCancelChat() {
        return playerItemCancelChat;
    }
}

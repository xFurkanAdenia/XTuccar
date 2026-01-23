package org.xfurkanadenia.xtuccar.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.xfurkanadenia.xtuccar.Logger;
import org.xfurkanadenia.xtuccar.XTuccar;
import org.xfurkanadenia.xtuccar.database.TuccarDAO;
import org.xfurkanadenia.xtuccar.model.Category;
import org.xfurkanadenia.xtuccar.model.MarketItem;
import org.xfurkanadenia.xtuccar.model.MarketSellingItem;
import org.xfurkanadenia.xtuccar.model.Validator;
import org.xfurkanadenia.xtuccar.util.Utils;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class TuccarManager {
    private TuccarDAO tuccarDAO;
    Cache<Integer, MarketSellingItem> cache;
    AtomicBoolean silent = new AtomicBoolean(false);
    public TuccarManager() {
        tuccarDAO = new TuccarDAO();
        cache = Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .maximumSize(1000)
                .removalListener((k, v, c) -> {
                    Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
                        if(!silent.get()) return;
                        tuccarDAO.saveItem((MarketSellingItem) v);
                    });
                })
                .build();
    }

    public Cache<Integer, MarketSellingItem> getCache() {
        return cache;
    }

    public void silentRemove(Integer id) {
        silent.set(true);
        cache.invalidate(id);
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            tuccarDAO.removeItem(id);
        });
        silent.set(false);
    }

    public TuccarDAO getTuccarDAO() {
        return tuccarDAO;
    }
    public void preload(Consumer<Void> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            Map<Integer, MarketSellingItem> items  = getTuccarDAO().getAllItems();
            if (!items.isEmpty()) items.forEach(cache::put);
            Bukkit.getScheduler().runTask(XTuccar.getInstance(), () -> callback.accept(null));
        });
    }

    public void getAllItems(Consumer<Map<Integer, MarketSellingItem>> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            Map<Integer, MarketSellingItem> items =  getTuccarDAO().getAllItems();
            items.forEach(cache::put);
            items.putAll(cache.asMap());
            Bukkit.getScheduler().runTask(XTuccar.getInstance(), () -> callback.accept(items));
        });
    }

    public void getItemBySeller(String seller, String itemId, Consumer<MarketSellingItem> callback) {
        MarketSellingItem[] mItem  = {null};
        mItem[0] = cache.asMap().values().stream().filter(v -> Objects.equals(v.getSeller(), seller) && Objects.equals(v.getItemId(), itemId)).findFirst().orElse(null);
        if(mItem[0] == null) mItem[0] = getTuccarDAO().getItemBySeller(seller, itemId);

        callback.accept(mItem[0]);
    }

    public MarketSellingItem getItemBySellerFromCache(String seller, String itemId) {
        return cache.asMap().values().stream().filter(v -> Objects.equals(v.getSeller(), seller) && Objects.equals(v.getItemId(), itemId)).findFirst().orElse(null);
    }

    public void getItemsBySeller(String seller, Consumer<Map<Integer, MarketSellingItem>> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            Map<Integer, MarketSellingItem> items = getTuccarDAO().getItemsBySeller(seller);
            items.forEach(cache::put);
            cache.asMap().forEach((k, v) -> {
                if(v.getSeller().equals(seller))
                    items.put(k, v);
            });
            Bukkit.getScheduler().runTask(XTuccar.getInstance(), () -> callback.accept(items));
        });
    }

    public void getItems(String itemid, Consumer<Map<Integer, MarketSellingItem>> callback) {
        // Pagination parametresi yok, ilk sayfayı göster
        getItems(itemid, 1, Integer.MAX_VALUE, (items, hasNext) -> callback.accept(items));
    }

    public void getItems(String itemid, int page, int pageSize, BiConsumer<Map<Integer, MarketSellingItem>, Boolean> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            Map<Integer, MarketSellingItem> allCached = cache.asMap().entrySet().stream()
                    .filter(e -> e.getValue().getItemId().equals(itemid))
                    .sorted(Comparator.comparingDouble(e -> e.getValue().getPrice()))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));

            int totalCount = allCached.size();

            // Eğer cache'de yeterli sayıda kayıt varsa DB'ye gitme
            if (totalCount >= (page - 1) * pageSize) {
                int startIndex = (page - 1) * pageSize;
                int endIndex = Math.min(startIndex + pageSize, totalCount);

                Map<Integer, MarketSellingItem> pageItems = new LinkedHashMap<>();
                List<Map.Entry<Integer, MarketSellingItem>> list = new ArrayList<>(allCached.entrySet());

                for (int i = startIndex; i < endIndex; i++) {
                    Map.Entry<Integer, MarketSellingItem> entry = list.get(i);
                    pageItems.put(entry.getKey(), entry.getValue());
                }

                boolean hasNext = endIndex < totalCount;
                Bukkit.getScheduler().runTask(XTuccar.getInstance(), () -> callback.accept(pageItems, hasNext));
                return;
            }

            // Eğer cache'de yoksa DB'den çek ve cache'e ekle
            try (Connection conn = getTuccarDAO().getConnection()) {
                String sql = """
                SELECT *, 
                       (SELECT COUNT(*) FROM items WHERE itemid = ?) as total_count
                FROM items 
                WHERE itemid = ? 
                ORDER BY price ASC 
                LIMIT ? OFFSET ?
            """;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, itemid);
                    stmt.setString(2, itemid);
                    stmt.setInt(3, pageSize);
                    stmt.setInt(4, (page - 1) * pageSize);

                    ResultSet rs = stmt.executeQuery();
                    Map<Integer, MarketSellingItem> pageItems = new LinkedHashMap<>();

                    while (rs.next()) {
                        int id = rs.getInt("id");
                        String seller = rs.getString("seller");
                        double price = rs.getDouble("price");
                        int amount = rs.getInt("amount");
                        totalCount = rs.getInt("total_count");

                        MarketSellingItem item = new MarketSellingItem(seller, itemid, price, amount, id);
                        pageItems.put(id, item);
                        cache.put(id, item);
                    }

                    boolean hasNextPage = (page * pageSize) < totalCount;

                    Bukkit.getScheduler().runTask(XTuccar.getInstance(), () ->
                            callback.accept(pageItems, hasNextPage));
                }
            } catch (Exception e) {
                e.printStackTrace();
                Bukkit.getScheduler().runTask(XTuccar.getInstance(), () ->
                        callback.accept(new LinkedHashMap<>(), false));
            }
        });
    }


    public void getItemsBySeller(String itemid, String seller, int page, int pageSize, BiConsumer<Map<Integer, MarketSellingItem>, Boolean> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            Map<Integer, MarketSellingItem> pageItems = new LinkedHashMap<>();

            try (Connection conn = getTuccarDAO().getConnection()) {
                // Database-first yaklaşım: sadece pagination ile gerekli veriyi al
                String sql = """
                SELECT *, 
                       (SELECT COUNT(*) FROM items WHERE itemid = ?) as total_count
                FROM items 
                WHERE itemid = ? 
                WHERE seller = ?
                ORDER BY price ASC 
                LIMIT ? OFFSET ?
                """;

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, itemid);
                    stmt.setString(2, seller);
                    stmt.setString(2, itemid);
                    stmt.setInt(3, pageSize);
                    stmt.setInt(4, (page - 1) * pageSize);

                    ResultSet rs = stmt.executeQuery();
                    int totalCount = 0;

                    while (rs.next()) {
                        double price = rs.getDouble("price");
                        int id = rs.getInt("id");
                        int amount = rs.getInt("amount");
                        totalCount = rs.getInt("total_count");

                        MarketSellingItem item = new MarketSellingItem(seller, itemid, price, amount, id);
                        pageItems.put(id, item);
                        cache.put(id, item);
                    }

                    boolean hasNextPage = (page * pageSize) < totalCount;

                    Bukkit.getScheduler().runTask(XTuccar.getInstance(), () ->
                            callback.accept(pageItems, hasNextPage));
                }

            } catch (Exception e) {
                e.printStackTrace();
                Bukkit.getScheduler().runTask(XTuccar.getInstance(), () ->
                        callback.accept(new LinkedHashMap<>(), false));
            }
        });
    }

    public void isPlayerSellingItem(String seller, String itemid, Consumer<Boolean> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            boolean found;
            found = getCache().asMap().values().stream().anyMatch(v -> v.getSeller().equals(seller) && v.getItemId().equals(itemid));
            if (!found) {
                found = getTuccarDAO().isPlayerSellingItem(seller, itemid);
            }
            callback.accept(found);
        });
    }

    public @Nullable MarketItem getMarketItem(String itemId) {
        return XTuccar.getInstance().getMarketItems().get(itemId);
    }

    public @Nullable MarketItem getMarketItem(ItemStack item) {
        if(item.getType().equals(Material.AIR)) return null;
        Map<String, MarketItem> items = XTuccar.getInstance().getMarketItems();
        for(MarketItem marketItem : items.values()) {
            if(marketItem.getItem().isSimilar(item)) return marketItem;
        }
        return null;
    }

    public void removeSellingItem(MarketSellingItem item, Consumer<Boolean> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            getTuccarDAO().removeItem(item.getId());
            silentRemove(item.getId());
            Bukkit.getScheduler().runTask(XTuccar.getInstance(), () -> callback.accept(true));
        });
    }

    public boolean sellItem(String seller, ItemStack item, double price, int amount) {
            MarketItem marketItem = getMarketItem(item);
            Player player = Bukkit.getPlayer(seller);
            if(player == null) return false;
            if(marketItem == null) {
                return false;
            }
            if(Utils.getItemAmount(item, player) < amount) {
                return false;
            }
            int id = getTuccarDAO().getId().getAndIncrement();
            getCache().put(id, new MarketSellingItem(seller, marketItem.getItemId(), price, amount, id));
            ItemStack i = new ItemStack(item);
            i.setAmount(amount);
            player.getInventory().removeItem(i);
            return true;
    }

    public void addStockToItem(String seller, ItemStack item, int amount, boolean removeItem, Consumer<Boolean> callback) {
        MarketItem marketItem = getMarketItem(item);
        String itemId = marketItem.getItemId();
        Player player = Bukkit.getPlayer(seller);
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            if (player == null) {
                callback.accept(false);
                return;
            }
            if (Utils.getItemAmount(item, player) < amount) {
                callback.accept(false);
                return;
            }
            isPlayerSellingItem(seller, itemId, isSelling -> {
                if (!isSelling) {
                    callback.accept(false);
                    return;
                }


                MarketSellingItem[] marketSellingItem = {null};
                cache.asMap().forEach((k, v) -> {
                    if (v.getSeller().equals(seller) && v.getItemId().equals(itemId)) {
                        marketSellingItem[0] = v;
                    }
                });

                if (marketSellingItem[0] == null)
                    marketSellingItem[0] = getTuccarDAO().getItemsBySeller(seller).values().stream().filter(v -> Objects.equals(v.getItemId(), itemId)).findFirst().orElse(null);

                if (marketSellingItem[0] != null) {
                    if (removeItem) {
                        ItemStack i = new ItemStack(item);
                        i.setAmount(amount);
                        player.getInventory().removeItem(i);
                    }
                    marketSellingItem[0].addAmount(amount);
                }

                callback.accept(true);
            });
        });
    }

    public void changePriceItem(String seller, String itemid, double price, Consumer<Boolean> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            MarketSellingItem marketSellingItem = getItemBySellerFromCache(seller, itemid);
            if(marketSellingItem == null) marketSellingItem = getTuccarDAO().getItemBySeller(seller, itemid);

            if(marketSellingItem != null) cache.put(marketSellingItem.getId(), marketSellingItem);
            else {
                callback.accept(false);
                return;
            }
            marketSellingItem.setPrice(price);
            callback.accept(true);
        });
    }

    public void cancelItem(String seller, String itemId, Integer amount, Consumer<Boolean> callback) {
        XTuccar instance = XTuccar.getInstance();
        Player player = Bukkit.getPlayer(seller);
        MarketItem marketItem = getMarketItem(itemId);
        if (player == null || marketItem == null) {
            callback.accept(false);
            return;
        }
        isPlayerSellingItem(seller, itemId, isSelling -> {
            if (!isSelling) {
                callback.accept(false);
                return;
            }
            getItemBySeller(seller, itemId, marketSellingItem -> {
                ItemStack cMarketItemStack = new ItemStack(marketItem.getItem());
                cMarketItemStack.setAmount(amount);
                if (!Validator.ValidateHasSpace(player, cMarketItemStack)) {
                    callback.accept(false);
                    return;
                }
                if(marketSellingItem.getAmount() < amount) {
                    callback.accept(false);
                    return;
                }
                marketSellingItem.removeAmount(amount);
                if(marketSellingItem.getAmount() <= 0) silentRemove(marketSellingItem.getId());
                callback.accept(true);
            });
        });

    }

    public List<MarketItem> getCategoryItems(Category category) {
        List<MarketItem> items = new ArrayList<>();
        XTuccar.getInstance().getMarketItems().forEach((k, v) -> {
            if(v.getCategory().getName().equals(category.getName())) items.add(v);
        });
        return items;
    }

    public void flushSync() {
        cache.asMap().forEach((k,v) ->  tuccarDAO.saveItem(v));
        cache.cleanUp();
    }

    public void saveAll(Consumer<Void> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(XTuccar.getInstance(), () -> {
            cache.asMap().forEach((k,v) -> tuccarDAO.saveItem(v));
            callback.accept(null);
        });
    }
}

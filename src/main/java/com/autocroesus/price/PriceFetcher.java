package com.autocroesus.price;

import com.autocroesus.config.AcDataStore;
import com.autocroesus.util.ChatUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class PriceFetcher {
   private static final HttpClient HTTP = HttpClient.newHttpClient();
   private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
   private static final String PRICING_URL = "https://whatyouth.ing/api/nofrills/v2/economy/get-item-pricing/";
   private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
   private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";

   private static CompletableFuture<String> fetchUrl(String url) {
      return HTTP.sendAsync(
                      HttpRequest.newBuilder()
                              .uri(URI.create(url))
                              .header("User-Agent", USER_AGENT)
                              .build(),
                      BodyHandlers.ofString()
              )
              .thenApply(resp -> {
                 if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp.body();
                 } else {
                    throw new RuntimeException("[Error 306] HTTP " + resp.statusCode() + " from " + url);
                 }
              });
   }

   public static CompletableFuture<String> updatePrices() {
      CompletableFuture<String> itemsFuture = fetchUrl(ITEMS_URL);
      AtomicReference<String> pricingFailReason = new AtomicReference<>(null);
      CompletableFuture<String> pricingSafe = fetchUrl(PRICING_URL).exceptionally(e -> {
         Throwable cause = e.getCause() != null ? e.getCause() : e;
         pricingFailReason.set(cause.getMessage());
         return null;
      });
      return CompletableFuture.allOf(itemsFuture, pricingSafe).thenApply(ignored -> {
         try {
            JsonObject resp = JsonParser.parseString(itemsFuture.join()).getAsJsonObject();
            if (!resp.get("success").getAsBoolean()) {
               throw new RuntimeException("[Error 303] Items API error: " + resp.get("cause").getAsString());
            }

            List<AcDataStore.SbItem> newItems = new ArrayList<>();

            for (JsonElement el : resp.getAsJsonArray("items")) {
               JsonObject obj = el.getAsJsonObject();
               AcDataStore.SbItem item = new AcDataStore.SbItem();
               item.id = obj.has("id") ? obj.get("id").getAsString() : "";
               item.name = obj.has("name") ? obj.get("name").getAsString() : "";
               item.tier = obj.has("tier") ? obj.get("tier").getAsString() : "COMMON";
               newItems.add(item);
            }

            AcDataStore.updateSbItems(newItems);
         } catch (Exception e) {
            throw new RuntimeException("[Error 304] Failed to process Items data: " + e.getMessage(), e);
         }

         String pricingBody = pricingSafe.join();
         if (pricingBody == null) {
            try {
               String bzBody = fetchUrl(BAZAAR_URL).join();
               JsonObject bzResp = JsonParser.parseString(bzBody).getAsJsonObject();
               if (!bzResp.get("success").getAsBoolean()) {
                  throw new RuntimeException("Bazaar API returned success=false");
               }

               JsonObject products = bzResp.getAsJsonObject("products");
               Map<String, AcDataStore.BzEntry> newBz = new HashMap<>();

               for (Entry<String, JsonElement> e : products.entrySet()) {
                  JsonObject qs = e.getValue().getAsJsonObject().getAsJsonObject("quick_status");
                  double buy = qs.get("buyPrice").getAsDouble();   // what buyers pay (sell-order side)
                  double sell = qs.get("sellPrice").getAsDouble(); // instant-sell side

                  double sellOrder = buy;
                  double instaSell = sell;

                  // No sell offers: fall back to the instant-sell price
                  if (sellOrder <= 0) sellOrder = instaSell;
                  // No buy orders: nothing to instant-sell into, use the order price
                  if (instaSell <= 0) instaSell = sellOrder;
                  // Thin-book spike: cap the order price at 2x the instant-sell price
                  if (instaSell > 0 && sellOrder > instaSell * 2.0) sellOrder = instaSell * 2.0;

                  AcDataStore.BzEntry bz = new AcDataStore.BzEntry();
                  bz.sellOrderValue = sellOrder;
                  bz.instaSellValue = instaSell;
                  newBz.put(e.getKey(), bz);
               }

               AcDataStore.updateBzValues(newBz);
               final String reason = pricingFailReason.get();
               net.minecraft.client.Minecraft.getInstance().execute(() ->
                       ChatUtil.msg("§e" + reason + " §f— used Hypixel Bazaar API instead. §7BIN prices were not updated.")
               );
               return null;
            } catch (Exception e) {
               Throwable cause = e.getCause() != null ? e.getCause() : e;
               return "§cPrices unavailable (" + pricingFailReason.get() + "), and Bazaar fallback failed (" + cause.getMessage() + ").";
            }
         }

         try {
            JsonObject pricing = JsonParser.parseString(pricingBody).getAsJsonObject();
            JsonObject bazaarObj = pricing.getAsJsonObject("bazaar");
            Map<String, AcDataStore.BzEntry> newBz = new HashMap<>();

            for (Entry<String, JsonElement> e : bazaarObj.entrySet()) {
               JsonObject vals = e.getValue().getAsJsonObject();
               AcDataStore.BzEntry bz = new AcDataStore.BzEntry();
               bz.sellOrderValue = vals.get("buy").getAsDouble();
               bz.instaSellValue = vals.get("sell").getAsDouble();
               newBz.put(e.getKey(), bz);
            }

            AcDataStore.updateBzValues(newBz);
            JsonObject auctionObj = pricing.getAsJsonObject("auction");
            Map<String, Double> newBins = new HashMap<>();

            for (Entry<String, JsonElement> e : auctionObj.entrySet()) {
               newBins.put(e.getKey(), e.getValue().getAsDouble());
            }

            AcDataStore.updateBinValues(newBins);
            return null;
         } catch (Exception e) {
            throw new RuntimeException("[Error 302] Failed to process pricing data: " + e.getMessage(), e);
         }
      });
   }
}
package com.autocroesus.price;

import com.autocroesus.config.AcDataStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class PriceFetcher {
   private static final HttpClient HTTP = HttpClient.newHttpClient();
   private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
   private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
   private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
   private static final String AUCTIONS_URL = "https://api.hypixel.net/v2/skyblock/auctions?page=";
   private static final Pattern PET_LEVEL_PREFIX = Pattern.compile("^\\[Lvl \\d+] ");

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

   private static String errMsg(Throwable e) {
      Throwable t = e;
      while (t.getCause() != null && t.getCause() != t) {
         t = t.getCause();
      }
      return t.getMessage() != null ? t.getMessage() : t.toString();
   }

   /**
    * Runs entirely on a background thread and never touches Minecraft.
    * Returns null on full success, or a warning string if Bazaar/BIN data could not be refreshed.
    */
   public static CompletableFuture<String> updatePrices() {
      return CompletableFuture.supplyAsync(() -> {
         try {
            JsonObject resp = JsonParser.parseString(fetchUrl(ITEMS_URL).join()).getAsJsonObject();
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
            throw new RuntimeException("[Error 304] Failed to process Items data: " + errMsg(e), e);
         }

         List<String> warnings = new ArrayList<>();

         try {
            updateBazaar();
         } catch (Exception e) {
            warnings.add("Bazaar: " + errMsg(e));
         }

         try {
            updateBins();
         } catch (Exception e) {
            warnings.add("BIN: " + errMsg(e));
         }

         if (warnings.isEmpty()) {
            return null;
         }

         return "§ePrices not fully updated (" + String.join("; ", warnings) + "). §fCached prices will be used for those. §7Run §f//ac api §7later to retry.";
      });
   }

   private static void updateBazaar() {
      JsonObject resp = JsonParser.parseString(fetchUrl(BAZAAR_URL).join()).getAsJsonObject();
      if (!resp.get("success").getAsBoolean()) {
         throw new RuntimeException("[Error 302] Bazaar API returned success=false");
      }

      Map<String, AcDataStore.BzEntry> newBz = new HashMap<>();
      for (Entry<String, JsonElement> e : resp.getAsJsonObject("products").entrySet()) {
         JsonObject qs = e.getValue().getAsJsonObject().getAsJsonObject("quick_status");
         double sellOrder = qs.get("buyPrice").getAsDouble();   // what buyers pay (sell-order side)
         double instaSell = qs.get("sellPrice").getAsDouble();  // instant-sell side

         if (sellOrder <= 0) sellOrder = instaSell;
         if (instaSell <= 0) instaSell = sellOrder;
         if (instaSell > 0 && sellOrder > instaSell * 2.0) sellOrder = instaSell * 2.0;

         AcDataStore.BzEntry bz = new AcDataStore.BzEntry();
         bz.sellOrderValue = sellOrder;
         bz.instaSellValue = instaSell;
         newBz.put(e.getKey(), bz);
      }

      AcDataStore.updateBzValues(newBz);
   }

   private static void updateBins() throws Exception {
      JsonObject first = JsonParser.parseString(fetchUrl(AUCTIONS_URL + 0).join()).getAsJsonObject();
      if (!first.get("success").getAsBoolean()) {
         throw new RuntimeException("[Error 305] Auctions API returned success=false");
      }

      int totalPages = first.get("totalPages").getAsInt();
      Map<String, Double> lowest = new HashMap<>();
      collectBins(first, lowest);

      ExecutorService pool = Executors.newFixedThreadPool(6);
      try {
         List<Future<Map<String, Double>>> futures = new ArrayList<>();
         for (int page = 1; page < totalPages; page++) {
            final int p = page;
            Callable<Map<String, Double>> task = () -> {
               JsonObject obj = JsonParser.parseString(fetchUrl(AUCTIONS_URL + p).join()).getAsJsonObject();
               Map<String, Double> local = new HashMap<>();
               collectBins(obj, local);
               return local;
            };
            futures.add(pool.submit(task));
         }

         for (Future<Map<String, Double>> f : futures) {
            for (Entry<String, Double> e : f.get().entrySet()) {
               lowest.merge(e.getKey(), e.getValue(), Math::min);
            }
         }
      } finally {
         pool.shutdown();
      }

      if (lowest.isEmpty()) {
         throw new RuntimeException("[Error 305] No BIN auctions found");
      }

      AcDataStore.updateBinValues(lowest);
   }

   private static void collectBins(JsonObject page, Map<String, Double> out) {
      for (JsonElement el : page.getAsJsonArray("auctions")) {
         JsonObject a = el.getAsJsonObject();
         if (!a.has("bin") || !a.get("bin").getAsBoolean()) {
            continue;
         }

         String id = extractItemId(a.get("item_bytes").getAsString());
         if (id == null || id.equals("ENCHANTED_BOOK")) {
            continue; // books are priced via the Bazaar
         }

         if (id.equals("PET")) {
            String name = PET_LEVEL_PREFIX.matcher(a.get("item_name").getAsString()).replaceFirst("").trim();
            String tier = a.has("tier") ? a.get("tier").getAsString() : "COMMON";
            id = "PET_" + name.toUpperCase().replace(' ', '_') + "_" + tier;
         }

         double price = a.get("starting_bid").getAsDouble();
         out.merge(id, price, Math::min);
      }
   }

   private static String extractItemId(String base64) {
      try {
         byte[] gz = Base64.getDecoder().decode(base64);
         byte[] raw;
         try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            raw = in.readAllBytes();
         }

         for (int i = 0; i + 7 < raw.length; i++) {
            if (raw[i] == 8 && raw[i + 1] == 0 && raw[i + 2] == 2 && raw[i + 3] == 'i' && raw[i + 4] == 'd') {
               int len = ((raw[i + 5] & 0xFF) << 8) | (raw[i + 6] & 0xFF);
               if (i + 7 + len <= raw.length) {
                  return new String(raw, i + 7, len, StandardCharsets.UTF_8);
               }
            }
         }
      } catch (Exception ignored) {

      }

      return null;
   }
}
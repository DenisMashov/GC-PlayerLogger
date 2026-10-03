package me.denis.playerlogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Sends batched embeds to Discord. A destination is either
 *   "webhook:https://discord.com/api/webhooks/..."   (no bot needed)
 *   "channel:123456789012345678"                     (uses discord.bot-token)
 * flush() blocks, so it is only called from an async task (or on shutdown).
 */
public final class DiscordWebhook {

    public record Item(String embedJson, boolean sensitive) {}

    private static final int MAX_QUEUE = 500;
    private static final Pattern CHANNEL_ID = Pattern.compile("\\d{5,25}");

    private final PlayerLoggerPlugin plugin;
    private final String username;
    private final String avatarUrl;
    private final String mention;
    private final String botToken;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, Queue<Item>> queues = new ConcurrentHashMap<>();
    private final Map<String, Long> pausedUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

    public DiscordWebhook(PlayerLoggerPlugin plugin, String username, String avatarUrl,
                          String mention, String botToken) {
        this.plugin = plugin;
        this.username = username == null ? "PlayerLogger" : username;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        this.mention = mention == null ? "" : mention;
        this.botToken = botToken == null ? "" : botToken.trim();
    }

    public boolean hasBotToken() {
        return !botToken.isBlank();
    }

    public void queue(String destination, Item item) {
        if (destination == null) return;

        if (destination.startsWith("webhook:")) {
            if (!destination.startsWith("webhook:https://")) {
                warn("Invalid webhook URL in config (must start with https://).");
                return;
            }
        } else if (destination.startsWith("channel:")) {
            String id = destination.substring(8);
            if (!CHANNEL_ID.matcher(id).matches()) {
                warn("Invalid channel-id in config: " + id);
                return;
            }
            if (botToken.isBlank()) {
                warn("A channel-id is configured but discord.bot-token is empty.");
                return;
            }
        } else {
            return;
        }

        Queue<Item> q = queues.computeIfAbsent(destination, k -> new ConcurrentLinkedQueue<>());
        if (q.size() < MAX_QUEUE) {
            q.add(item);
        }
    }

    public void flush(boolean force) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Queue<Item>> entry : queues.entrySet()) {
            Queue<Item> q = entry.getValue();
            if (q.isEmpty()) continue;
            if (!force && now < pausedUntil.getOrDefault(entry.getKey(), 0L)) continue;

            List<Item> batch = new ArrayList<>(10);
            Item it;
            while (batch.size() < 10 && (it = q.poll()) != null) {
                batch.add(it);
            }
            send(entry.getKey(), batch);
        }
    }

    private void send(String destination, List<Item> batch) {
        boolean viaBot = destination.startsWith("channel:");
        String target = destination.substring(destination.indexOf(':') + 1);
        String url = viaBot
                ? "https://discord.com/api/v10/channels/" + target + "/messages"
                : target;

        boolean ping = !mention.isBlank() && batch.stream().anyMatch(Item::sensitive);

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (!viaBot) {
            sb.append("\"username\":").append(LogManager.q(username));
            first = false;
            if (!avatarUrl.isBlank()) {
                sb.append(",\"avatar_url\":").append(LogManager.q(avatarUrl));
            }
        }
        if (ping) {
            if (!first) sb.append(',');
            sb.append("\"content\":").append(LogManager.q(mention));
            first = false;
        }
        if (!first) sb.append(',');
        sb.append("\"allowed_mentions\":{\"parse\":").append(ping ? "[\"roles\",\"users\"]" : "[]").append("}");
        sb.append(",\"embeds\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(batch.get(i).embedJson());
        }
        sb.append("]}");

        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "DiscordBot (PlayerLogger, 1.0.0)")
                    .POST(HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8));
            if (viaBot) {
                rb.header("Authorization", "Bot " + botToken);
            }

            HttpResponse<String> res = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            String where = viaBot ? "channel " + target : "a webhook";

            if (status == 429) {
                double seconds = 5.0;
                try {
                    seconds = Double.parseDouble(res.headers().firstValue("retry-after").orElse("5"));
                } catch (NumberFormatException ignored) {
                    // keep default
                }
                pausedUntil.put(destination, System.currentTimeMillis() + (long) (seconds * 1000) + 500L);
                Queue<Item> q = queues.get(destination);
                if (q != null) q.addAll(batch);
            } else if (status == 401 || status == 403) {
                warn("Discord rejected the request for " + where + " (HTTP " + status + "). "
                        + (viaBot ? "Check the bot token and that the bot can send messages in that channel."
                                  : "Check the webhook URL."));
            } else if (status == 404) {
                warn("Discord could not find " + where + " (HTTP 404). Check the webhook URL / channel-id.");
            } else if (status >= 400) {
                warn("Discord returned HTTP " + status + " for " + where + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            warn("Could not reach Discord: " + e.getMessage());
        }
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(msg);
        if (last == null || now - last > 60_000L) {
            lastWarn.put(msg, now);
            plugin.getLogger().warning(msg);
        }
    }
}

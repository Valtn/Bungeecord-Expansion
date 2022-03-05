package com.extendedclip.papi.bungeeexpansion;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.clip.placeholderapi.expansion.Configurable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Taskable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class BungeeExpansion extends PlaceholderExpansion implements PluginMessageListener, Taskable, Configurable {

    private static final String MESSAGE_CHANNEL = "BungeeCord";
    private static final String SERVERS_CHANNEL = "GetServers";
    private static final String PLAYERS_CHANNEL = "PlayerCount";
    private static final String CONFIG_INTERVAL = "check_interval";

    private static final Splitter SPLITTER = Splitter.on(",").trimResults();


    private int totalPlayerCount                     = 0;
    private final Map<String, Integer>        counts = new HashMap<>();
    private final AtomicReference<BukkitTask> cached = new AtomicReference<>();


    @Override
    public String getIdentifier() {
        return "bungee";
    }

    @Override
    public String getAuthor() {
        return "clip";
    }

    @Override
    public String getVersion() {
        return "2.0-valtn";
    }

    @Override
    public Map<String, Object> getDefaults() {
        return Collections.singletonMap(CONFIG_INTERVAL, 30);
    }


    @Override
    public String onRequest(final OfflinePlayer player, String identifier) {
        final int value;

        identifier = identifier.toLowerCase();

        switch (identifier) {
            case "all":
            case "total":
                value = this.totalPlayerCount;
                break;
            default:
                if (identifier.contains(",")) {
                    Stream<String> servers = Arrays.stream(identifier.split(","));
                    value = servers.mapToInt(server -> counts.computeIfAbsent(server, count -> 0)).sum();
                } else {
                    value = counts.computeIfAbsent(identifier, count -> 0);
                }
                break;
        }

        return String.valueOf(value);
    }

    @Override
    public void start() {
        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(getPlaceholderAPI(), () -> {

            // Ask for the total player count.
            this.sendPlayersChannelMessage("ALL");

            // Ask for server specific player counts.
            counts.keySet().forEach(this::sendPlayersChannelMessage);

        }, 20L * 2L, 20L * getLong(CONFIG_INTERVAL, 30));


        final BukkitTask prev = cached.getAndSet(task);
        if (prev != null) {
            prev.cancel();
        } else {
            Bukkit.getMessenger().registerOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
            Bukkit.getMessenger().registerIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
        }
    }

    @Override
    public void stop() {
        final BukkitTask prev = cached.getAndSet(null);
        if (prev == null) {
            return;
        }

        prev.cancel();
        counts.clear();

        Bukkit.getMessenger().unregisterOutgoingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(getPlaceholderAPI(), MESSAGE_CHANNEL, this);
    }


    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!MESSAGE_CHANNEL.equals(channel)) {
            return;
        }

        //noinspection UnstableApiUsage
        final ByteArrayDataInput in = ByteStreams.newDataInput(message);
        switch (in.readUTF()) {
            case PLAYERS_CHANNEL:
                String server = in.readUTF();
                int count = in.readInt();
                if (server.equalsIgnoreCase("ALL")) {
                    totalPlayerCount = count;
                } else {
                    counts.put(server.toLowerCase(), count);
                }
                break;
            case SERVERS_CHANNEL:
                SPLITTER.split(in.readUTF()).forEach(serverName -> counts.putIfAbsent(serverName, 0));
                break;
        }
    }


    private void sendServersChannelMessage() {
        sendMessage(SERVERS_CHANNEL, out -> { });
    }

    private void sendPlayersChannelMessage(final String serverName) {
        sendMessage(PLAYERS_CHANNEL, out -> out.writeUTF(serverName));
    }

    private void sendMessage(final String channel, final Consumer<ByteArrayDataOutput> consumer) {
        final Player player = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
        if (player == null) {
            return;
        }

        //noinspection UnstableApiUsage
        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(channel);

        consumer.accept(out);

        player.sendPluginMessage(getPlaceholderAPI(), MESSAGE_CHANNEL, out.toByteArray());
    }

}

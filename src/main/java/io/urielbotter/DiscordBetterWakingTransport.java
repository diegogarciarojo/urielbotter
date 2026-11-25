package io.urielbotter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DiscordBetterWakingTransport implements BetterWakingCoordinator.Discord {
    private static final Logger LOG = LoggerFactory.getLogger(DiscordBetterWakingTransport.class);

    private final JDA jda;
    private final BotConfig config;
    private final Blacklist blacklist;

    DiscordBetterWakingTransport(JDA jda, BotConfig config, Blacklist blacklist) {
        this.jda = jda;
        this.config = config;
        this.blacklist = blacklist;
    }

    @Override
    public Set<String> targets() {
        return channels().stream().map(GuildMessageChannel::getId).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void send(String channelId, String message, Instant deadline, BooleanSupplier current) {
        channels().stream().filter(channel -> channelId.equals(channel.getId())).findFirst().ifPresent(channel -> {
            try {
                channel.sendMessage(message)
                        .deadline(deadline.toEpochMilli())
                        .addCheck(() -> current.getAsBoolean() && targets().contains(channelId))
                        .queue(ignored -> { }, failure -> LOG.debug("No se entregó BetterWaking a {}: {}",
                                channelId, failure.getClass().getSimpleName()));
            } catch (RuntimeException failure) {
                LOG.debug("No se pudo encolar BetterWaking para {}: {}", channelId, failure.getClass().getSimpleName());
            }
        });
    }

    private List<GuildMessageChannel> channels() {
        if (jda.getStatus() != JDA.Status.CONNECTED) {
            return List.of();
        }
        var category = jda.getCategoryById(config.targetCategoryId());
        return category == null ? List.of() : RelayListener.destinations(category, config, blacklist).stream()
                .filter(GuildMessageChannel::canTalk).toList();
    }
}

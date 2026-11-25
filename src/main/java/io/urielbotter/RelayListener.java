package io.urielbotter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RelayListener extends ListenerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(RelayListener.class);
    private static final Emoji SENT = Emoji.fromUnicode("✅");
    private final BotConfig config;
    private final Blacklist blacklist;
    private final BetterWakingCoordinator coordinator;

    RelayListener(BotConfig config, Blacklist blacklist, BetterWakingCoordinator coordinator) {
        this.config = config;
        this.blacklist = blacklist;
        this.coordinator = coordinator;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().equals(event.getJDA().getSelfUser())) {
            return;
        }

        Message incoming = event.getMessage();
        String content = incoming.getContentRaw();
        if (!config.commandChannelId().equals(event.getChannel().getId())) {
            if (coordinator != null && event.getAuthor().isBot() && !event.isWebhookMessage()) {
                List<String> descriptions = Stream.concat(Stream.of(content),
                                incoming.getEmbeds().stream().map(MessageEmbed::getDescription))
                        .filter(Objects::nonNull).toList();
                coordinator.receive(event.getChannel().getId(), event.getAuthor().getId(), true, false, descriptions);
            }
            return;
        }
        if (BetterWakingWire.isPrivateCommand(content)) {
            reply(incoming, "BetterWaking reserva poll y plan para la coordinación automática.");
            return;
        }
        String[] parts = content.strip().split("\\s+", 3);
        switch (parts[0]) {
            case "add", "delete" -> updateBlacklist(incoming, parts);
            default -> relay(incoming, content);
        }
    }

    private void updateBlacklist(Message incoming, String[] parts) {
        boolean adding = parts[0].equals("add");
        if (parts.length != 2 || !BotConfig.validId(parts[1])) {
            reply(incoming, "Uso de " + parts[0] + " <ID de canal>");
            return;
        }
        String channelId = parts[1];
        if (channelId.equals(config.commandChannelId())) {
            reply(incoming, "El canal de comandos siempre está excluido de la retransmisión.");
            return;
        }

        try {
            if (adding) {
                reply(incoming, blacklist.add(channelId)
                        ? "Canal añadido a la blacklist (" + channelId + ")."
                        : "Ese canal ya está en la blacklist.");
            } else {
                reply(incoming, blacklist.remove(channelId)
                        ? "Canal retirado de la blacklist (" + channelId + ")."
                        : "Ese canal no está en la blacklist.");
            }
        } catch (IOException e) {
            LOG.warn("No se pudo escribir ignored_channels.json", e);
            reply(incoming, "No se pudo guardar la blacklist. Revisa los permisos del archivo.");
        }
    }

    private void relay(Message incoming, String content) {
        if (content.isBlank()) {
            return;
        }
        if (content.length() > Message.MAX_CONTENT_LENGTH) {
            reply(incoming, "El texto supera el límite de " + Message.MAX_CONTENT_LENGTH + " caracteres de Discord.");
            return;
        }
        Category category = incoming.getJDA().getCategoryById(config.targetCategoryId());
        if (category == null) {
            reply(incoming, "La categoría de destino ya no está disponible.");
            return;
        }

        List<CompletableFuture<Boolean>> deliveries = destinations(category, config, blacklist).stream()
                .map(channel -> send(channel, content)).toList();
        if (deliveries.isEmpty()) {
            reply(incoming, "La categoría no tiene canales disponibles para la retransmisión.");
            return;
        }

        CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).thenRun(() -> {
            long sent = deliveries.stream().filter(CompletableFuture::join).count();
            if (sent == deliveries.size()) {
                try {
                    incoming.addReaction(SENT).queue(ignored -> { },
                            failure -> LOG.warn("No se pudo añadir la reacción. {}", failure.toString()));
                } catch (RuntimeException failure) {
                    LOG.warn("No se pudo añadir la reacción. {}", failure.toString());
                }
            } else {
                reply(incoming, "Mensaje enviado a " + sent + " de " + deliveries.size() + " canales.");
            }
        });
    }

    static List<GuildMessageChannel> destinations(Category category, BotConfig config, Blacklist blacklist) {
        return category.getChannels().stream()
                .filter(channel -> channel.getType() == ChannelType.TEXT || channel.getType() == ChannelType.NEWS)
                .filter(channel -> !config.commandChannelId().equals(channel.getId()) && !blacklist.contains(channel.getId()))
                .map(GuildMessageChannel.class::cast).toList();
    }

    private CompletableFuture<Boolean> send(GuildMessageChannel channel, String content) {
        try {
            return channel.sendMessage(content).submit().handle((ignored, failure) -> {
                if (failure == null) {
                    return true;
                }
                LOG.warn("No se pudo enviar al canal {}. {}", channel.getId(), failure.toString());
                return false;
            });
        } catch (RuntimeException failure) {
            LOG.warn("No se pudo enviar al canal {}. {}", channel.getId(), failure.toString());
            return CompletableFuture.completedFuture(false);
        }
    }

    private void reply(Message incoming, String status) {
        send(incoming.getGuildChannel(), status);
    }
}

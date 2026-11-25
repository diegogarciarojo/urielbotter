package io.urielbotter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BetterWakingDiscordTest {
    private static final String COMMAND = "10";
    private static final String FIRST = "11";
    private static final String SECOND = "12";
    private static final String IGNORED = "13";
    private static final String OTHER_CATEGORY = "14";
    private static final String UNBOUND = "15";
    private static final String FIRST_BOT = "21";
    private static final String SECOND_BOT = "22";
    private static final BotConfig CONFIG = new BotConfig("test-placeholder", COMMAND, "20");

    @TempDir
    Path temp;

    private final MutableClock clock = new MutableClock();
    private final FakeDiscord discord = new FakeDiscord(clock);
    private Blacklist blacklist;
    private BetterWakingCoordinator coordinator;
    private RelayListener relay;
    private boolean apiAvailable = true;
    private int priorityLength;
    private int apiReads;

    @BeforeEach
    void setup() throws IOException {
        blacklist = Blacklist.open(temp.resolve("ignored_channels.json"));
        blacklist.add(IGNORED);
        var config = new CoordinatorConfig(true, ".", 1, Duration.ofMinutes(5), Duration.ofMinutes(2),
                Map.of(FIRST, FIRST_BOT, SECOND, SECOND_BOT, IGNORED, "23", OTHER_CATEGORY, "24"));
        coordinator = new BetterWakingCoordinator(config, clock,
                new DiscordBetterWakingTransport(discord.jda, CONFIG, blacklist), () -> {
                    apiReads++;
                    return apiAvailable
                            ? Optional.of(new QueueApiClient.Snapshot(clock.instant(), 100, priorityLength, 90, 1))
                            : Optional.empty();
                });
        relay = new RelayListener(CONFIG, blacklist, coordinator);
    }

    @AfterEach
    void close() {
        coordinator.close();
    }

    @Test
    void targetsOnlyBoundCategoryChannelsAndProcessesTrustedPlainAndEmbedReplies() {
        coordinator.openRound();
        discord.deliver();
        assertEquals(List.of(FIRST, SECOND), discord.destinations());
        String firstRequest = discord.requestFor(FIRST);
        String secondRequest = discord.requestFor(SECOND);
        assertNotEquals(firstRequest, secondRequest);
        String offline = state(firstRequest, "OFFLINE COORDINATED REGULAR - - - - 1 false");
        message(FIRST, "80", true, false, offline);
        message(FIRST, FIRST_BOT, false, false, offline);
        message(FIRST, FIRST_BOT, true, true, offline);
        message(FIRST, FIRST_BOT, true, false, state(secondRequest, "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        message(FIRST, FIRST_BOT, true, false, "", new EmbedBuilder().setDescription(offline).build());
        message(FIRST, FIRST_BOT, true, false, state(firstRequest, "BLOCKED COORDINATED REGULAR - - - - 1 false"));
        message(SECOND, SECOND_BOT, true, false, state(secondRequest, "QUEUED COORDINATED REGULAR 400 - - - 1 false"));
        discord.delivered.clear();

        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        coordinator.finishRound();
        discord.deliver();

        long now = clock.instant().getEpochSecond();
        assertEquals(List.of(FIRST, SECOND), discord.destinations());
        assertEquals(".betterWaking plan " + firstRequest + " " + (now + 360) + " " + now,
                discord.messageFor(FIRST));
        assertEquals(".betterWaking plan " + secondRequest + " " + (now + 360) + " -", discord.messageFor(SECOND));
        assertEquals(1, apiReads);
        message(FIRST, FIRST_BOT, true, false, "BW1 ACK " + firstRequest + " APPLIED");
        coordinator.finishRound();
        assertTrue(discord.pending.isEmpty());
    }

    @Test
    void rejectsLateRepliesAndReplaysAndWithholdsLeaseWhenApiFails() {
        coordinator.openRound();
        discord.deliver();
        String oldRequest = discord.requestFor(FIRST);
        discord.delivered.clear();
        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        message(FIRST, FIRST_BOT, true, false, state(oldRequest, "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        coordinator.finishRound();
        assertEquals(0, apiReads);

        clock.advance(Duration.ofMinutes(2));
        coordinator.openRound();
        discord.deliver();
        String currentRequest = discord.requestFor(FIRST);
        discord.delivered.clear();
        message(FIRST, FIRST_BOT, true, false, state(oldRequest, "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        message(FIRST, FIRST_BOT, true, false, state(currentRequest, "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        apiAvailable = false;
        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        coordinator.finishRound();
        discord.deliver();

        assertEquals(1, apiReads);
        assertTrue(discord.delivered.isEmpty());
    }

    @Test
    void rechecksBlacklistAtSendTimeAndExpiresRequestsHeldByRateLimits() throws IOException {
        coordinator.openRound();
        blacklist.add(SECOND);
        clock.advance(Duration.ofSeconds(15));
        discord.deliver();
        assertTrue(discord.delivered.isEmpty());

        clock.advance(Duration.ofMinutes(2));
        coordinator.openRound();
        discord.deliver();
        assertEquals(List.of(FIRST), discord.destinations());
        String request = discord.requestFor(FIRST);
        discord.delivered.clear();
        message(FIRST, FIRST_BOT, true, false, state(request, "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        coordinator.finishRound();
        assertEquals(1, discord.pending.size());
        blacklist.add(FIRST);
        discord.deliver();
        assertTrue(discord.delivered.isEmpty());

        blacklist.remove(FIRST);
        clock.advance(Duration.ofMinutes(2));
        coordinator.openRound();
        discord.deliver();
        String latestRequest = discord.requestFor(FIRST);
        discord.delivered.clear();
        message(FIRST, FIRST_BOT, true, false, state(latestRequest, "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        coordinator.finishRound();
        assertEquals(1, discord.pending.size());
        clock.advance(Duration.ofSeconds(20));
        discord.deliver();
        assertTrue(discord.delivered.isEmpty());
    }

    @Test
    void uncalibratedOfflinePriorityReceivesNoHoldAndCanFallBack() {
        priorityLength = 10;
        coordinator.openRound();
        discord.deliver();
        message(FIRST, FIRST_BOT, true, false,
                state(discord.requestFor(FIRST), "OFFLINE COORDINATED PRIORITY - - - - 1 false"));
        message(SECOND, SECOND_BOT, true, false,
                state(discord.requestFor(SECOND), "OFFLINE AUTONOMOUS REGULAR - - - - 1 false"));
        discord.delivered.clear();
        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        coordinator.finishRound();
        discord.deliver();

        assertEquals(1, apiReads);
        assertTrue(discord.delivered.isEmpty());
    }

    @Test
    void dryRunProxyDoesNotTakeTheLiveProxyAssignment() {
        coordinator.openRound();
        discord.deliver();
        message(FIRST, FIRST_BOT, true, false,
                state(discord.requestFor(FIRST), "OFFLINE COORDINATED REGULAR - - - - 1 true"));
        message(SECOND, SECOND_BOT, true, false,
                state(discord.requestFor(SECOND), "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        discord.delivered.clear();
        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        coordinator.finishRound();
        discord.deliver();

        String immediate = " " + clock.instant().getEpochSecond();
        assertTrue(discord.messageFor(FIRST).endsWith(immediate));
        assertTrue(discord.messageFor(SECOND).endsWith(immediate));
    }

    @Test
    void authenticatedLocalCoverageRaisesTheFleetTargetAboveTheConfiguredFloor() {
        assertEquals(1, coldStarts(1));
        clock.advance(Duration.ofMinutes(2));
        assertEquals(2, coldStarts(2));
    }

    @Test
    void ordinaryRelayAndBlacklistStillWorkAndPrivateCommandsCannotBeSpoofed() throws IOException {
        message(COMMAND, "99", false, false, "  .betterWaking status  ");
        assertEquals(List.of(FIRST, SECOND, UNBOUND), discord.destinations());
        assertEquals("  .betterWaking status  ", discord.messageFor(FIRST));
        assertEquals(1, discord.reactions);
        discord.delivered.clear();

        message(COMMAND, "99", false, false, "add " + FIRST);
        assertTrue(Blacklist.open(temp.resolve("ignored_channels.json")).contains(FIRST));
        assertEquals(List.of(COMMAND), discord.destinations());
        discord.delivered.clear();
        message(COMMAND, "99", false, false, "delete " + FIRST);
        assertFalse(blacklist.contains(FIRST));
        discord.delivered.clear();
        for (String command : List.of(".betterWaking poll 1", ". betterWaking plan 1 2 -",
                ".\tbetterWaking plan 1 2 -", "!zenith:BETTERWAKING PLAN 1 2 -")) {
            message(COMMAND, "99", false, false, command);
            assertEquals(List.of(COMMAND), discord.destinations());
            assertTrue(discord.messageFor(COMMAND).contains("coordinación automática"));
            discord.delivered.clear();
        }
        for (String text : List.of("BetterWaking plan", "Revisemos BetterWaking plan", ".betterWaking\u00A0plan")) {
            message(COMMAND, "99", false, false, text);
            assertEquals(List.of(FIRST, SECOND, UNBOUND), discord.destinations());
            assertEquals(text, discord.messageFor(FIRST));
            discord.delivered.clear();
        }
        message(FIRST, FIRST_BOT, true, false, "Hi from Zenith!");
        assertTrue(discord.delivered.isEmpty());
    }

    @Test
    void optionalConfigLeavesLegacyInstallationUntouched() throws IOException {
        Path path = temp.resolve("better_waking.json");
        assertFalse(CoordinatorConfig.load(path).enabled());
        assertFalse(Files.exists(path));
        Files.writeString(path, """
                {"enabled":true,"proxyBotIds":{"11":"21"},"commandPrefix":"!","targetCoverage":2}
                """);
        var config = CoordinatorConfig.load(path);
        assertTrue(config.enabled());
        assertEquals("!", config.commandPrefix());
        assertEquals(2, config.targetCoverage());
        assertEquals(Duration.ofMinutes(2), config.pollInterval());
        assertEquals(Map.of("11", "21"), config.proxyBotIds());
    }

    private static String state(String request, String fields) {
        return "BW1 STATE " + request + " " + fields;
    }

    private long coldStarts(int requestedCoverage) {
        discord.delivered.clear();
        coordinator.openRound();
        discord.deliver();
        message(FIRST, FIRST_BOT, true, false, state(discord.requestFor(FIRST),
                "OFFLINE COORDINATED REGULAR - - - - " + requestedCoverage + " false"));
        message(SECOND, SECOND_BOT, true, false,
                state(discord.requestFor(SECOND), "OFFLINE COORDINATED REGULAR - - - - 1 false"));
        discord.delivered.clear();
        clock.advance(BetterWakingCoordinator.RESPONSE_WINDOW);
        coordinator.finishRound();
        discord.deliver();
        String now = " " + clock.instant().getEpochSecond();
        return discord.delivered.stream().filter(delivery -> delivery.text().endsWith(now)).count();
    }

    private void message(String channel, String author, boolean bot, boolean webhook, String content,
                         MessageEmbed... embeds) {
        relay.onMessageReceived(discord.event(channel, author, bot, webhook, content, List.of(embeds)));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-24T22:00:00Z");

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeDiscord {
        private final Clock clock;
        private final Map<String, GuildMessageChannelUnion> channels = new LinkedHashMap<>();
        private final List<Delivery> delivered = new ArrayList<>();
        private final List<Pending> pending = new ArrayList<>();
        private final JDA jda;
        private int reactions;

        FakeDiscord(Clock clock) {
            this.clock = clock;
            List<GuildChannel> attached = List.of(channel(COMMAND, ChannelType.TEXT), channel(FIRST, ChannelType.TEXT),
                    channel(SECOND, ChannelType.NEWS), channel(IGNORED, ChannelType.TEXT), channel(UNBOUND, ChannelType.TEXT));
            channel(OTHER_CATEGORY, ChannelType.TEXT);
            var category = mock(Category.class, (proxy, method, args) -> switch (method.getName()) {
                case "getChannels" -> attached;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            var self = mock(SelfUser.class, (proxy, method, args) -> switch (method.getName()) {
                case "getId" -> "90";
                default -> throw new UnsupportedOperationException(method.getName());
            });
            jda = mock(JDA.class, (proxy, method, args) -> switch (method.getName()) {
                case "getCategoryById" -> CONFIG.targetCategoryId().equals(args[0]) ? category : null;
                case "getSelfUser" -> self;
                case "getStatus" -> JDA.Status.CONNECTED;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private GuildMessageChannelUnion channel(String id, ChannelType type) {
            GuildMessageChannelUnion channel = mock(GuildMessageChannelUnion.class,
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getId" -> id;
                        case "getType" -> type;
                        case "canTalk" -> true;
                        case "sendMessage" -> sendMessage(id, args[0].toString());
                        default -> throw new UnsupportedOperationException(method.getName());
                    }, MessageChannelUnion.class);
            channels.put(id, channel);
            return channel;
        }

        private MessageCreateAction sendMessage(String id, String text) {
            long[] deadline = {Long.MAX_VALUE};
            List<BooleanSupplier> checks = new ArrayList<>();
            var delivery = new Delivery(id, text);
            return mock(MessageCreateAction.class, (proxy, method, args) -> switch (method.getName()) {
                case "deadline" -> {
                    deadline[0] = (Long) args[0];
                    yield proxy;
                }
                case "addCheck" -> {
                    checks.add((BooleanSupplier) args[0]);
                    yield proxy;
                }
                case "queue" -> {
                    pending.add(new Pending(delivery, deadline[0], checks));
                    yield null;
                }
                case "submit" -> {
                    delivered.add(delivery);
                    yield CompletableFuture.completedFuture(null);
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        MessageReceivedEvent event(String channelId, String author, boolean bot, boolean webhook,
                                   String content, List<MessageEmbed> embeds) {
            var channel = channels.get(channelId);
            var user = mock(User.class, (proxy, method, args) -> switch (method.getName()) {
                case "getId" -> author;
                case "isBot" -> bot;
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
            var message = mock(Message.class, (proxy, method, args) -> switch (method.getName()) {
                case "getIdLong" -> 100L;
                case "getJDA" -> jda;
                case "getChannel", "getGuildChannel" -> channel;
                case "getAuthor" -> user;
                case "isWebhookMessage" -> webhook;
                case "getContentRaw" -> content;
                case "getEmbeds" -> embeds;
                case "addReaction" -> mock(RestAction.class, (action, actionMethod, ignored) -> {
                    if (!actionMethod.getName().equals("queue")) {
                        throw new UnsupportedOperationException(actionMethod.getName());
                    }
                    reactions++;
                    return null;
                });
                default -> throw new UnsupportedOperationException(method.getName());
            });
            return new MessageReceivedEvent(jda, 1, message);
        }

        void deliver() {
            for (var request : pending) {
                if (clock.millis() < request.deadline()
                        && request.checks().stream().allMatch(BooleanSupplier::getAsBoolean)) {
                    delivered.add(request.delivery());
                }
            }
            pending.clear();
        }

        List<String> destinations() {
            return delivered.stream().map(Delivery::channel).sorted().toList();
        }

        String messageFor(String id) {
            return delivered.stream().filter(delivery -> id.equals(delivery.channel())).findFirst().orElseThrow().text();
        }

        String requestFor(String id) {
            return messageFor(id).split(" ")[2];
        }

        private static <T> T mock(Class<T> type, InvocationHandler handler) {
            return mock(type, handler, new Class<?>[0]);
        }

        private static <T> T mock(Class<T> type, InvocationHandler handler, Class<?>... additionalTypes) {
            Class<?>[] interfaces = new Class<?>[additionalTypes.length + 1];
            interfaces[0] = type;
            System.arraycopy(additionalTypes, 0, interfaces, 1, additionalTypes.length);
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), interfaces, handler));
        }

        private record Delivery(String channel, String text) {}

        private record Pending(Delivery delivery, long deadline, List<BooleanSupplier> checks) {}
    }
}

package io.urielbotter;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.requests.GatewayIntent;

public final class Main {
    private static final Path CONFIG_FILE = Path.of("config.json");
    private static final Path BLACKLIST_FILE = Path.of("ignored_channels.json");
    private static final Path LOCK_FILE = Path.of(".urielbotter.lock");

    private Main() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (InvalidTokenException e) {
            printError("Discord rechazó el token. Ejecuta ./launch --setup para actualizarlo.");
            System.exit(1);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            printError(e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException, InterruptedException {
        if (args.length > 1) {
            throw new IllegalArgumentException("Consulta las opciones con ./launch --help.");
        }
        String option = args.length == 0 ? "" : args[0];
        if (option.equals("--help")) {
            System.out.println("""
                    ./launch               Inicia UrielBotter y solicita la configuración si falta.
                    ./launch --setup       Vuelve a solicitar la configuración e inicia el bot.
                    ./launch --setup-only  Guarda la configuración sin conectarse a Discord.
                    """);
            return;
        }
        boolean configure = option.equals("--setup") || option.equals("--setup-only");
        if (!option.isEmpty() && !configure) {
            throw new IllegalArgumentException("Opción desconocida. Consulta ./launch --help.");
        }

        try (var channel = FileChannel.open(LOCK_FILE, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) {
                throw new IllegalStateException("UrielBotter ya se está ejecutando en esta carpeta.");
            }

            BotConfig config = readConfig(configure);
            if (config == null || configure) {
                config = Setup.prompt(config);
                config.save(CONFIG_FILE);
                System.out.println("[UrielBotter] Configuración guardada.");
            }

            Blacklist blacklist;
            try {
                blacklist = Blacklist.open(BLACKLIST_FILE);
            } catch (JsonParseException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Revisa ignored_channels.json. Debe ser una lista JSON de IDs de canal.");
            }
            if (!option.equals("--setup-only")) {
                connect(config, blacklist);
            }
        }
    }

    private static BotConfig readConfig(boolean configure) throws IOException {
        try {
            return BotConfig.load(CONFIG_FILE);
        } catch (NoSuchFileException e) {
            return null;
        } catch (JsonParseException | IllegalArgumentException e) {
            if (!configure) {
                throw new IllegalArgumentException("config.json no es válido. Ejecuta ./launch --setup.");
            }
            System.out.println("[UrielBotter] Se solicitarán todos los valores porque config.json no es válido.");
            return null;
        }
    }

    private static void connect(BotConfig config, Blacklist blacklist) throws InterruptedException {
        System.out.println("[UrielBotter] Conectando con Discord...");
        JDA jda = JDABuilder.createLight(config.token(), GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(jda::shutdownNow, "urielbotter-shutdown"));
        try {
            awaitDiscord(jda);
            GuildChannel commandChannel = jda.getGuildChannelById(config.commandChannelId());
            if (commandChannel == null
                    || (commandChannel.getType() != ChannelType.TEXT && commandChannel.getType() != ChannelType.NEWS)) {
                throw new IllegalStateException("No se encontró el canal de comandos. Revisa su ID y los permisos del bot.");
            }
            if (jda.getCategoryById(config.targetCategoryId()) == null) {
                throw new IllegalStateException("No se encontró la categoría destino. Revisa su ID y los permisos del bot.");
            }

            jda.addEventListener(new RelayListener(config, blacklist));
            System.out.println("[UrielBotter] Conectado como " + jda.getSelfUser().getName() + ".");
            jda.awaitShutdown();
        } finally {
            if (jda.getStatus() != JDA.Status.SHUTDOWN) {
                jda.shutdownNow();
            }
        }
    }

    private static void awaitDiscord(JDA jda) throws InterruptedException {
        try {
            jda.awaitReady();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Discord cerró la conexión antes de iniciar el bot. Verifica el token y Message Content Intent.");
        }
    }

    private static void printError(String message) {
        System.err.println("[UrielBotter] " + message);
    }
}

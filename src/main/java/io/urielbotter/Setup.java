package io.urielbotter;

import java.io.Console;
import java.util.Arrays;

final class Setup {
    private Setup() {}

    static BotConfig prompt(BotConfig existing) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Ejecuta ./launch en una terminal interactiva para configurar el bot.");
        }

        System.out.println("[UrielBotter] Configuración de Discord");
        System.out.println("Activa Message Content Intent en la sección Bot del portal de Discord.");
        String token = readToken(console, existing);
        String commandChannelId = readId(console, "ID del canal de comandos",
                existing == null ? null : existing.commandChannelId(), null);
        String categoryId = readId(console, "ID de la categoría destino",
                existing == null ? null : existing.targetCategoryId(), commandChannelId);
        return new BotConfig(token, commandChannelId, categoryId);
    }

    private static String readToken(Console console, BotConfig existing) {
        while (true) {
            System.out.println();
            System.out.println("Token del bot de Discord (entrada oculta)");
            if (existing != null) {
                System.out.println("Pulsa Enter para conservar el token guardado.");
            }

            char[] input = console.readPassword("> ");
            if (input == null) {
                throw cancelled();
            }
            String token;
            try {
                token = new String(input).strip();
            } finally {
                Arrays.fill(input, '\0');
            }
            if (token.isEmpty() && existing != null) {
                return existing.token();
            }
            if (BotConfig.validToken(token)) {
                return token;
            }
            System.out.println("Introduce un token sin espacios.");
        }
    }

    private static String readId(Console console, String label, String existing, String reservedId) {
        while (true) {
            System.out.println();
            System.out.println(existing == null ? label : label + " [" + existing + "]");
            String input = console.readLine("> ");
            if (input == null) {
                throw cancelled();
            }
            String id = input.isBlank() ? existing : input.strip();
            if (BotConfig.validId(id) && !id.equals(reservedId)) {
                return id;
            }
            System.out.println("Introduce un ID numérico de Discord distinto del otro ID.");
        }
    }

    private static IllegalStateException cancelled() {
        return new IllegalStateException("Configuración cancelada.");
    }
}

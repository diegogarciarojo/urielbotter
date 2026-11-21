# UrielBotter

Bot de Discord en Java 21. Retransmite texto desde un canal de comandos a los canales de texto y de anuncios de una categoría de Discord.

## Instalar en Ubuntu

Ejecuta `uname -m` en EC2 y sube a tu carpeta personal el ZIP correspondiente, por SCP u otro método:

| Resultado | Archivo |
| --- | --- |
| `x86_64` | `UrielBotter-linux-amd64.zip` |
| `aarch64` | `UrielBotter-linux-arm64.zip` |

Los ZIP incluyen Java 21. Estos comandos muestran el caso `x86_64`:

```bash
sudo apt update
sudo apt install -y unzip tmux
mkdir -p ~/UrielBotter
cd ~/UrielBotter
unzip ~/UrielBotter-linux-amd64.zip
tmux new -s urielbotter
./launch
```

Con `Ctrl+B`, seguido de `D`, puedes dejar la sesión de tmux en segundo plano. Para volver, ejecuta `tmux attach -t urielbotter`. Inicia de nuevo la sesión si reinicias la instancia.

## Configurar Discord

1. Instala el bot en el servidor de Discord y activa **Message Content Intent** en **Bot → Privileged Gateway Intents** de su aplicación.
2. En Discord activa **Modo desarrollador** para copiar IDs al hacer clic derecho sobre un canal o una categoría.
3. En el primer `./launch` introduce el token del bot, el ID del canal de comandos y el ID de la categoría destino. La entrada del token permanece oculta.
4. El bot necesita **Ver canal** en el origen y los destinos y **Enviar mensajes** para retransmitir y responder a los comandos. Para reaccionar con ✅ en el canal de origen, necesita **Añadir reacciones** y **Leer el historial de mensajes**.

La configuración se guarda en `config.json`, con permiso `0600` en Ubuntu. Contiene el token. Para volver al asistente ejecuta `./launch --setup`. Puedes pulsar Enter para conservar los valores existentes. `./launch --setup-only` guarda la configuración y sale antes de conectarse a Discord.

La instancia necesita salida HTTPS/WSS hacia Discord por TCP 443. UrielBotter funciona sin servidor HTTP.

[Configuración de Discord](https://docs.discord.com/developers/events/gateway#enabling-privileged-intents)

## Usar la blacklist

Envía estos comandos desde el canal de comandos:

```text
add <ID del canal>
delete <ID del canal>
```

Al configurar UrielBotter se crea `ignored_channels.json`. `add` agrega el ID, `delete` lo retira y los cambios persisten entre reinicios. El canal de comandos siempre queda excluido de los destinos. El resto del texto publicado en ese canal se retransmite a los destinos habilitados. Restringe quién puede escribir en él si solo determinados miembros deben poder administrar la blacklist y retransmitir.

Puedes copiar un `ignored_channels.json` del bot anterior a la carpeta de instalación antes de iniciar `./launch`.

## Compilar

Usa Java 21 o superior, Maven y Python 3.10 o superior si vas a empaquetar las dos arquitecturas:

```bash
mvn clean verify
python3 scripts/package_linux.py --cache-dir .release-cache --output-dir dist
```

El JAR se genera en `target/urielbotter.jar`. El empaquetador incluye las distribuciones de Eclipse Temurin fijadas en el script y comprueba sus SHA-256.

El flujo de instalación y primera ejecución sigue el del launcher de ZenithProxy. [Launcher V3](https://github.com/rfresh2/ZenithProxy/releases/tag/launcher-v3) · [Guía de ZenithProxy](https://wiki.2b2t.vc/Setup/#usage)

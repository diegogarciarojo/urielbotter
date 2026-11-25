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
2. En Discord activa **Modo desarrollador**. Haz clic derecho sobre el canal de comandos y elige **Copiar ID del canal**. Para la categoría destino, haz clic derecho sobre el encabezado que agrupa los canales a los que quieres retransmitir y copia su ID.
3. En el primer `./launch` introduce el token del bot, el ID del canal de comandos y el ID de la categoría destino.
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

## Coordinar BetterWaking

La coordinación es opcional. Para habilitarla, guarda `better_waking.json` junto a `launch` y reinicia UrielBotter. Conserva `config.json` e `ignored_channels.json` de tu instalación.

```json
{
  "enabled": true,
  "commandPrefix": ".",
  "targetCoverage": 1,
  "marginSeconds": 300,
  "pollSeconds": 120,
  "proxyBotIds": {
    "100000000000000001": "200000000000000001",
    "100000000000000002": "200000000000000002"
  }
}
```

En `proxyBotIds` reemplaza cada clave con el ID del canal principal de una proxy y cada valor con el ID de usuario de **su bot de ZenithProxy**. Usa el mismo prefijo en las proxies gestionadas. `targetCoverage` fija el mínimo para el objetivo de cuentas simultáneamente en el mundo. Una proxy coordinada en operación real puede elevar el objetivo con `betterWaking coverage`. `marginSeconds` adelanta los relevos y `pollSeconds` acepta intervalos de 60 a 240 segundos.

Las proxies deben estar en la categoría destino. Activa **Message Content Intent** en ambas aplicaciones y concede a UrielBotter **Ver canal** y **Enviar mensajes** en los canales gestionados. En cada ZenithProxy, ejecuta `discord ignoreOtherBots off` en su consola y configura BetterWaking con el ID de usuario de **UrielBotter**. Restringe quién puede escribir en los canales de control. [Instalación y comandos de BetterWaking](https://github.com/diegogarciarojo/BetterWaking) · [Intents de Discord](https://docs.discord.com/developers/events/gateway#message-content-intent)

El mismo `add` excluye un canal de futuras rondas y del relay. Una orden ya aceptada puede durar hasta seis minutos. Si quieres detener también el comportamiento autónomo, desactiva BetterWaking en esa proxy. Si `better_waking.json` falta, está desactivado o es inválido, el bot continúa como relay. Las órdenes internas `poll` y `plan` se envían directamente a cada proxy desde el coordinador.

La coordinación necesita salida HTTPS por TCP 443 a `api.2b2t.vc`, además de la conexión habitual a Discord. Si la API falla, las proxies conservan sus colas mientras el relay sigue disponible.

### Actualizar una instalación existente desde código fuente

Compila primero con Java 21 y Maven. Detén el bot anterior con `Ctrl+C` en su sesión tmux y copia solo el JAR sobre el de tu instalación. Los cambios de configuración del coordinador se leen al arrancar.

```bash
mvn clean verify
install -m 0644 target/urielbotter.jar "$HOME/UrielBotter/urielbotter.jar"
cd "$HOME/UrielBotter"
./launch
```

Ese destino corresponde a una instalación hecha desde ZIP, con `runtime/` junto a `launch`. En una instalación desde código fuente, compila en esa carpeta y vuelve a ejecutar `./launch`, que toma `target/urielbotter.jar`.

## Compilar

Usa Java 21 o superior, Maven y Python 3.10 o superior si vas a empaquetar las dos arquitecturas:

```bash
mvn clean verify
python3 scripts/package_linux.py --cache-dir .release-cache --output-dir dist
```

El JAR se genera en `target/urielbotter.jar`. El empaquetador incluye las distribuciones de Eclipse Temurin fijadas en el script y comprueba sus SHA-256.

El flujo de instalación y primera ejecución sigue el del launcher de ZenithProxy. [Launcher V3](https://github.com/rfresh2/ZenithProxy/releases/tag/launcher-v3) · [Guía de ZenithProxy](https://wiki.2b2t.vc/Setup/#usage)

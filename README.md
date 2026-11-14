# Heaven

Launcher de Android pensado para consolas portátiles como la **Retroid Pocket 6**: es la pantalla
de inicio que muestra todas tus apps (YouTube, Play Store, emuladores, Ajustes…) y se maneja
entera con **sticks, cruceta y botones**, sin tocar la pantalla. También funciona al tacto.

## Qué hace

- **Inicio** con reloj grande, tarjeta para continuar con la última app, estadísticas en tiempo real y una barra de apps fijadas.
- **Estadísticas** a elegir: batería, consumo en vatios, temperaturas, CPU, memoria, almacenamiento interno y tarjeta SD, ping, Wi‑Fi y refresco de pantalla; en tres tamaños y cuatro estilos (segmentos, anillo, gráfica, número).
- **Cajón de apps** con categorías automáticas (Juegos, Emuladores, Multimedia, Apps, Sistema), apps ocultas y búsqueda.
- **Iconos a tu gusto**: paquetes de iconos de Play Store (formato estándar ADW/Nova), una imagen de la galería por app, forma, tamaño y nombre.
- **Reloj** en once estilos (matriz de puntos, segmentos, grande, fino, bloques, contorno, apilado, en palabras, binario, analógico y terminal), con fecha y segundos.
- **Tema**: 18 fuentes para toda la interfaz y color de acento con rueda de color (tono, saturación, brillo y claros/oscuros).
- **Fondos**: trama de puntos, negro AMOLED, dos animados, imagen propia o el fondo de Android.
- **Widgets reales de Android** en la segunda página de Inicio.
- **Panel rápido** (brillo, volumen, Wi‑Fi, Bluetooth, notificaciones) y **atajos de botones** configurables.
- **Puntero con el mando** para apps que solo entienden toques (servicio de accesibilidad opcional).
- **Temas y copias de seguridad** exportables a un archivo `.zip`.
- Sonidos y vibración al navegar (se pueden apagar).

- **Carrusel de Inicio**: la app enfocada queda en el centro, más grande; botón **Jugar** que abre tu frontend de juegos.
- **Menús en ventanas flotantes** y transiciones de entrada entre pantallas.
- **Sonido**: tres paquetes de efectos sintetizados (suave, arcade, mecánico) y **música de fondo** con tus propias canciones, solo en Inicio y en el cajón.
- **Modos de la consola** (Retroid/AYN): rendimiento y ventilador desde el panel rápido, usando el servicio PServer del firmware. El formato de ese servicio está documentado por [ClusterTune](https://github.com/AurelioB/ClusterTune) (MIT, © Aurelio Bernal).

- **Estadísticas en juego**: con el servicio de accesibilidad activo, mantén **L1 + R1 + SELECT** en cualquier app para mostrar u ocultar una tira con FPS, temperaturas, uso de GPU, consumo y batería.
- **Detector de mosaicos**: para modos que cada consola guarda a su manera (estilo de mando, gatillos), Heaven compara los ajustes del sistema antes y después de cambiar el mosaico de Android y aprende cuál es.

## Instalar

1. Descarga `palanca.apk` desde **Releases** de este repositorio (se genera solo con cada cambio en `main`).
2. Ábrelo en la consola y permite instalar apps de origen desconocido.
3. Pulsa Home y elige **Heaven** → *Siempre*. Para volver al launcher anterior: Ajustes de Android → Apps → Apps predeterminadas → App de inicio.

Las actualizaciones se instalan encima y conservan tu configuración.

## Controles

| Botón | Inicio | Cajón de apps |
|---|---|---|
| Cruceta / stick izquierdo | Moverse | Moverse |
| A | Abrir | Abrir |
| X | Opciones de la app | Opciones de la app |
| Y | Buscar | Buscar |
| B | — | Volver |
| L1 / R1 | Cambiar de página (Inicio / Widgets) | Cambiar de categoría |
| Stick derecho | Reordenar la app fijada | Saltar tres filas |
| START | Custom | Custom |
| SELECT | CNTRL (panel rápido) | Fijar / quitar de Inicio |
| Abajo (en la barra) | Abrir el cajón | — |

Atajos por defecto (se cambian en *Custom › Mandos*): mantener SELECT → Ajustes de Android,
mantener START → última app, L2 → panel rápido, R2 → apps recientes, L3 → buscar, R3 → notificaciones.

Si A y B te salen cruzados, activa *Custom › Mandos › Diseño Nintendo*.

### Puntero con mando

Actívalo en *Custom › Mandos › Puntero con mando* (abre los ajustes de accesibilidad de Android).
Después, en cualquier app: **mantén L3 + R3** para encender o apagar el puntero, o elige otro botón en
*Custom › Mandos › Botón del puntero* (por ejemplo un botón trasero al que hayas asignado una tecla).
Con el puntero encendido: stick izquierdo o cruceta = mover · A = tocar · X = toque largo · B = atrás ·
stick derecho o L1/R1 = desplazar · START = salir. Apagado no intercepta nada, así que los juegos reciben el mando intacto.
Mientras está encendido, la app de debajo deja de recibir el mando (lo recibe el puntero).
El servicio también es el que permite abrir *Apps recientes* desde un atajo.

## Límites conocidos

- Android no deja a las apps encender o apagar Wi‑Fi y Bluetooth: el panel rápido abre el panel del sistema.
- Cambiar el brillo necesita el permiso «Modificar ajustes del sistema»; el panel lo pide la primera vez.
- El contenido de los widgets responde al toque (o al puntero), no al foco del mando.

## Configurar la consola desde el PC

La guía completa para la sesión local de Claude Code (portátil + consola por USB) está en
[`docs/sesion-local.md`](docs/sesion-local.md).

## Compilar

```bash
./gradlew :app:assembleRelease
```

El APK queda en `app/build/outputs/apk/release/`. Se firma con `palanca-sideload.keystore`, una clave
incluida en el repositorio solo para instalar a mano (no sirve para publicar en una tienda).

```bash
./gradlew :app:testDebugUnitTest
```

ejecuta las pruebas de la entrada del mando y de la navegación.

```bash
./gradlew :app:recordRoborazziDebug
```

dibuja todas las pantallas a PNG en `app/build/shots/` sin necesidad de dispositivo (con apps de ejemplo).

## Estructura

- `input/` — traduce teclas y ejes del mando a eventos propios, con repetición y pulsación larga.
- `data/` — configuración (JSON), lista de apps y categorías, estadísticas de uso, copias de seguridad.
- `icons/` — paquetes de iconos y dibujo de iconos con máscara.
- `system/` — acciones del sistema, sonidos, widgets y el servicio del puntero.
- `ui/` — pantallas (Compose). Cada pantalla lleva su propia selección: no depende del foco de Android.

Fuentes incluidas (todas con licencia SIL Open Font License, de Google Fonts): Space Grotesk, Space Mono, Outfit, Sora, Rubik,
Comfortaa, Exo 2, Oxanium, Chakra Petch, Orbitron, Audiowide, Bebas Neue, Playfair Display, JetBrains Mono, DM Mono,
Share Tech Mono, Major Mono Display, Pixelify Sans, Silkscreen, VT323 y Press Start 2P.

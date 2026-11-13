# Palanca

Launcher de Android pensado para consolas portátiles como la **Retroid Pocket 6**: es la pantalla
de inicio que muestra todas tus apps (YouTube, Play Store, emuladores, Ajustes…) y se maneja
entera con **sticks, cruceta y botones**, sin tocar la pantalla. También funciona al tacto.

## Qué hace

- **Inicio** con reloj grande, tarjeta para continuar con la última app, estadísticas en tiempo real y una barra de apps fijadas.
- **Estadísticas** a elegir: batería, consumo en vatios, temperaturas, CPU, memoria, almacenamiento interno y tarjeta SD, ping, Wi‑Fi y refresco de pantalla; en tres tamaños y cuatro estilos (segmentos, anillo, gráfica, número).
- **Cajón de apps** con categorías automáticas (Juegos, Emuladores, Multimedia, Apps, Sistema), apps ocultas y búsqueda.
- **Iconos a tu gusto**: paquetes de iconos de Play Store (formato estándar ADW/Nova), una imagen de la galería por app, forma, tamaño y nombre.
- **Reloj** en cuatro estilos (matriz de puntos, segmentos, grande, fino), color de acento, fecha y segundos.
- **Fondos**: trama de puntos, negro AMOLED, dos animados, imagen propia o el fondo de Android.
- **Widgets reales de Android** en la segunda página de Inicio.
- **Panel rápido** (brillo, volumen, Wi‑Fi, Bluetooth, notificaciones) y **atajos de botones** configurables.
- **Puntero con el mando** para apps que solo entienden toques (servicio de accesibilidad opcional).
- **Temas y copias de seguridad** exportables a un archivo `.zip`.
- Sonidos y vibración al navegar (se pueden apagar).

## Instalar

1. Descarga `palanca.apk` desde **Releases** de este repositorio (se genera solo con cada cambio en `main`).
2. Ábrelo en la consola y permite instalar apps de origen desconocido.
3. Pulsa Home y elige **Palanca** → *Siempre*. Para volver al launcher anterior: Ajustes de Android → Apps → Apps predeterminadas → App de inicio.

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
| START | Personalizar | Personalizar |
| SELECT | Panel rápido | Fijar / quitar de Inicio |
| Abajo (en la barra) | Abrir el cajón | — |

Atajos por defecto (se cambian en *Personalizar › Mandos*): mantener SELECT → Ajustes de Android,
mantener START → última app, L2 → panel rápido, R2 → apps recientes, L3 → buscar, R3 → notificaciones.

Si A y B te salen cruzados, activa *Personalizar › Mandos › Diseño Nintendo*.

### Puntero con mando

Actívalo en *Personalizar › Mandos › Puntero con mando* (abre los ajustes de accesibilidad de Android).
Después, en cualquier app: **mantén L3 + R3** para encender o apagar el puntero.
Con el puntero encendido: stick izquierdo o cruceta = mover · A = tocar · X = toque largo · B = atrás ·
stick derecho o L1/R1 = desplazar · START = salir. Apagado no intercepta nada, así que los juegos reciben el mando intacto.
Mientras está encendido, la app de debajo deja de recibir el mando (lo recibe el puntero).
El servicio también es el que permite abrir *Apps recientes* desde un atajo.

## Límites conocidos

- Android no deja a las apps encender o apagar Wi‑Fi y Bluetooth: el panel rápido abre el panel del sistema.
- Cambiar el brillo necesita el permiso «Modificar ajustes del sistema»; el panel lo pide la primera vez.
- El contenido de los widgets responde al toque (o al puntero), no al foco del mando.

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

Fuentes: Space Grotesk y Space Mono (SIL Open Font License).

# Sesión local: configurar la Retroid Pocket 6 desde la G14

Este documento tiene dos lectores:

- **Tú**, para preparar el portátil y la consola y arrancar la sesión (partes 1 y 2).
- **La sesión local de Claude Code**, que no recuerda nada de la conversación en la nube: todo lo que
  necesita saber está aquí (partes 3 a 9).

Lo que no he podido comprobar desde la nube va marcado como **sin verificar**. La sesión local debe
comprobarlo en la consola antes de darlo por bueno.

---

## 1. Qué preparar antes de abrir la sesión

### En la G14 (Windows)

1. Claude Code instalado (ya lo tienes, dentro de la app de escritorio de Claude).
2. Una carpeta de trabajo vacía, por ejemplo `C:\Pocket`. Dentro se guardará todo lo descargado.
3. Al menos 40 GB libres en disco para descargas temporales.
4. El portátil enchufado y con la suspensión desactivada mientras dure la sesión.
5. Ten localizadas estas rutas (la sesión te las pedirá):
   - Carpeta con tus BIOS.
   - Carpeta con tus juegos (por sistema, si ya los tienes ordenados).
   - Carpeta de Lossless Scaling en Steam (donde está `Lossless.dll`); suele ser
     `C:\Program Files (x86)\Steam\steamapps\common\Lossless Scaling`.
6. Un lector de tarjetas para la microSD (para la prueba de capacidad de la etapa 0 y para copiar
   la biblioteca de juegos, que por lector va mucho más rápido que por ADB).
7. Si Windows no reconoce la consola al conectarla, la sesión instalará el **Google USB Driver**
   oficial (`developer.android.com`).

### En la Pocket

1. Batería por encima del 60 % o enchufada.
2. **Opciones de desarrollador** activadas: Ajustes › Acerca del dispositivo › tocar siete veces
   «Número de compilación».
3. Dentro de Opciones de desarrollador:
   - **Depuración USB** activada.
   - **Pantalla activa mientras se carga** activada (evita que se bloquee a mitad de un paso).
   - **Inhabilitar restricciones de procesos secundarios** activada, si aparece (la piden DroidDeck y
     las apps de juegos de PC).
4. Conecta la Pocket a la G14 con un cable USB‑C **de datos**. En el aviso de la consola marca
   «Permitir siempre desde este ordenador» y acepta.
5. En la notificación de USB de la consola elige **Transferencia de archivos**.
6. Heaven ya instalado y elegido como app de inicio (lo está).
7. El servicio de accesibilidad de Heaven activado (Custom › Mandos › Puntero con mando).
8. Heaven sin restricciones de batería (Ajustes › Apps › Heaven › Batería › Sin restricciones), para
   que Android no cierre el puntero ni las estadísticas en juego.
9. Sesión de Google iniciada en Play Store (varias apps salen de ahí).

### Lo que solo haces tú

- Escribir contraseñas (Google, Steam, Epic) y aprobar pagos.
- Aceptar avisos en la pantalla de la consola (depuración, permisos, instalación).
- Aportar tus propios BIOS, claves y juegos. La sesión no los descarga.
- Aprobar cada lista de descargas antes de que empiece.

---

## 2. Cómo abrir la sesión local

1. Abre la app de escritorio de Claude y entra en la parte de Claude Code.
2. Crea una sesión nueva que se ejecute **en este equipo** (local), no en la nube, y elige la carpeta
   `C:\Pocket` como carpeta de trabajo.
3. Pega el mensaje de arranque de abajo tal cual.
4. Cuando la sesión pida permiso para ejecutar comandos (`adb`, descargas), acéptalo.

### Mensaje de arranque (copiar y pegar)

```text
Vas a configurar mi Retroid Pocket 6 desde este portátil (ASUS ROG Zephyrus G14, Windows), con la
consola conectada por USB-C y la depuración USB ya autorizada.

1. Clona https://github.com/calosmail101/widgets- en esta carpeta y lee entero el archivo
   docs/sesion-local.md. Es tu guía: contiene el contexto, las reglas, los datos ya conocidos de la
   consola, las etapas y lo que tienes que investigar.
2. Sigue sus reglas de trabajo (parte 3) sin excepciones.
3. Empieza por la etapa 1 (puente ADB) y la investigación para Heaven (parte 6), y luego sigue las
   etapas en orden. Al terminar cada etapa, dime qué quedó listo, qué debo probar yo y qué falló.

Antes de descargar nada, enséñame la lista con sus fuentes y espera a que la apruebe.
```

### Si la sesión se corta o abres una nueva

La sesión va apuntando lo que hace en `C:\Pocket\progreso.md`. Para retomar, abre otra sesión local
en la misma carpeta y pega:

```text
Lee widgets-/docs/sesion-local.md y progreso.md en esta carpeta y continúa la configuración de mi
Retroid Pocket 6 desde donde quedó. Comprueba primero con adb devices que la consola sigue conectada.
```

### Reparto recomendado (no hace falta hacerlo todo de una vez)

| Sesión | Etapas | Lo que te toca a ti |
|---|---|---|
| A | 0, 1, 2 e investigación de Heaven (parte 6) | Lanzar H2testw, aceptar avisos, cambiar mosaicos cuando te lo pida |
| B | 3 y 4 (drivers y PS2) | Dar la ruta de BIOS y de Lossless Scaling, probar un juego |
| C | 5, 6 y 7 (PC, frontends, emuladores) | Iniciar sesión en Steam/Epic, elegir frontend, dar la ruta de los juegos |
| D | 8 (streaming) | Iniciar sesión en Tailscale y PSN, emparejar con el PIN |
| E | 9 y 10 (ports y dejar Heaven a punto) | Probar y decidir qué se queda |

H2testw tarda horas: mientras corre se puede avanzar con las etapas 1 y 2, que no usan la tarjeta.

---

## 3. Reglas de trabajo para la sesión local

1. **Fuentes oficiales únicamente**: el GitHub de cada proyecto o Play Store. Nunca resubidas ni
   páginas espejo. Varios proyectos tienen dominios falsos (DroidDeck es un caso claro).
2. **Lista antes de descargar**: antes de cada lote, muestra nombre, versión, URL exacta y tamaño, y
   espera aprobación.
3. **Verifica la versión más reciente** de cada app y driver en su repositorio antes de instalar. Las
   versiones de este documento son de noviembre de 2026.
4. **No descargues BIOS, claves ni juegos.** Los aporta el usuario desde sus carpetas.
5. **Contraseñas, pagos y avisos en pantalla** los resuelve el usuario. Avísale cuando toque y espera.
6. **Nada irreversible sin preguntar**: no desbloquear el bootloader, no flashear, no formatear la
   memoria interna, no borrar datos de apps existentes. Formatear la microSD solo tras confirmarlo.
7. **Comprueba cada paso** con ADB (que la app quedó instalada, que el archivo llegó con su tamaño)
   y enseña una captura (`adb exec-out screencap -p`) cuando sirva para confirmar.
8. **Una cosa de afinado a la vez**: ClusterTune y el control de CPU/GPU de GameNative nunca activos
   a la vez.
9. Al acabar cada etapa: qué quedó listo, qué debe probar el usuario, problemas encontrados.
10. Al final: un resumen con todo lo instalado, versiones, driver por app y ajustes clave, guardado
    en `C:\Pocket\resumen.md`.
11. **Diario de progreso**: tras cada paso terminado, añade una línea a `C:\Pocket\progreso.md`
    (fecha, etapa, qué se hizo, versión instalada, qué queda). Es lo que permite retomar si la
    sesión se corta.
12. **No desinstales una app para reinstalarla** sin preguntar: desinstalar borra sus datos
    (partidas, configuración). Si una instalación falla por firma distinta, para y pregunta.
13. Todo lo descargado se guarda en `C:\Pocket\descargas\<etapa>` con su nombre original, y se
    comprueba el hash cuando el proyecto lo publica.

---

## 4. Datos ya conocidos de la consola

Salen de los diagnósticos que exporta Heaven.

| Dato | Valor |
|---|---|
| Modelo | Retroid Pocket 6 (Moorechip), stick arriba |
| Chip | Qualcomm QCS8550 (Snapdragon 8 Gen 2, Adreno 740) |
| Memoria | 12 GB RAM / 128 GB |
| Pantalla | 1080×1920, 60 y 120 Hz |
| Sistema | Android 13 (API 33), compilación `RP6_V1.0.0.406_20260616` |
| Mando integrado | «Retroid Pocket Controller»; la cruceta llega como eje HAT, no como teclas |
| microSD | Kingston Canvas Go! Plus 512 GB, exFAT, almacenamiento portátil |

### Servicio privilegiado (PServer)

La consola trae un servicio de fábrica, `PServerBinder`, que ejecuta comandos **como root** para
cualquier app (`uid=0`, contexto `u:r:pservice:s0`). Es lo que usa ClusterTune para funcionar sin
root, y lo que usa Heaven para cambiar modos. Desde ADB no hace falta: `adb shell` ya permite
`settings put`.

### Ajustes del fabricante (tabla `system`)

| Clave | Significado | Valores |
|---|---|---|
| `performance_mode` | Modo de rendimiento | 0 Standard · 1 Performance · 2 High performance (confirmado en la consola) |
| `fan_mode` | Modo del ventilador | 4 Smart · 5 Sport · 6 Personalizado (confirmado); 0 apagado y 1 silencioso según ClusterTune, **sin verificar** aquí |
| `fan_speed` | Velocidad en modo personalizado | rango **sin verificar** (se asume 0–100) |
| `fan_thermal_management_area` | Sensor de referencia | `CPU` |
| `joystick_light_enabled` | Luces de los sticks | `1,1` / `0,0` (confirmado) |
| `joystick_led_light_picker_color` | Color de las luces | `#aarrggbb,#aarrggbb` (confirmado) |
| `led_light_brightness_percent` | Brillo de las luces | 0.0–1.0 (confirmado) |
| `percent_80_charge_limit` | Límite de carga al 80 % | 1 activado; **sin verificar** el 0 |
| `temp_abxy_layout_mode` | Desconocido | Cambiarlo **no** cambia el estilo de mando |

### Lo que no se puede leer

- RPM del ventilador: `/sys/class/hwmon` está vacío incluso como root.
- `dumpsys SurfaceFlinger --timestats -dump` devolvió vacío con las estadísticas sin activar.

### Heaven

- Repositorio: `https://github.com/calosmail101/widgets-` (rama `main`).
- Nombre visible: **Heaven**. Identificador del paquete: `dev.palanca.launcher` (no cambia).
- Servicio de accesibilidad: `dev.palanca.launcher/dev.palanca.launcher.system.PointerService`.
- Configuración: `/data/data/dev.palanca.launcher/files/config.json` (solo legible como la propia app
  o root; con ADB sin root, usar `adb shell run-as` no sirve en la versión de lanzamiento).
- El APK se publica solo en Releases del repositorio con cada cambio en `main`.

---

## 5. Etapas de configuración

### Etapa 0 · Verificar la microSD (antes de usarla)

1. Con la tarjeta en el lector de la G14, descarga **H2testw** de su fuente oficial (heise.de).
2. Ejecuta la prueba completa de escritura y verificación. Tarda horas en 512 GB.
3. Si informa de menos capacidad real o de errores, la tarjeta es falsa o está dañada: para aquí.
4. Si pasa, borra los archivos `.h2w`, formatea en **exFAT** y ponla en la Pocket como
   **almacenamiento portátil** (no «interno»).

### Etapa 1 · Puente G14 → Pocket

1. Descarga **platform-tools** (ADB) de la web oficial de Android (`developer.android.com`) y
   **scrcpy** del GitHub de Genymobile. Descomprime en `C:\Pocket\tools`.
2. `adb devices` debe mostrar la consola como `device`. Si sale `unauthorized`, el usuario debe
   aceptar el aviso en la pantalla de la Pocket.
3. Abre scrcpy para que el usuario vea la pantalla de la consola en el portátil.
4. Anota: `adb shell getprop ro.build.display.id` y `adb shell df -h /data /storage/*`.

### Etapa 2 · Base de la Pocket

- Actualización: Ajustes › Sistema › Retroid Pocket FOTA. La consola está en la 406.
- Certificación de Play Protect confirmada.
- Ajustes: 120 Hz; tiempo de pantalla 30 min; «tocar para activar» apagado; animaciones del sistema
  a 0.5x; icono flotante apagado; sin bloqueo al despertar; No molestar activado.
- **Disposición de botones**: el usuario quiere Xbox (ABXY). Hazlo desde el panel de la consola, y
  de paso resuelve la investigación 6.1.
- Calibrar los sticks y dejar la zona muerta en 5–8 %.
- Instalar: **Obtainium** (e importar el Obtainium Emulation Pack), **MiXplorer**, **ClusterTune**
  (repositorio `AurelioB/ClusterTune`, no copias), **Syncthing‑Fork**, **Key Mapper**, **CHDroid** y
  **UniPatcher**.
- ClusterTune en **Stock**. Trae un perfil para el QCS8550. Hay un informe de un usuario de Pocket 6
  al que un underclock moderado dejó de bastarle en el núcleo PPSSPP de RetroArch tras una
  actualización; por eso se empieza en Stock y se compara después.

### Etapa 3 · Drivers Turnip

Descargar en zip **sin descomprimir**, de los repositorios oficiales:

- Mr Purple T30 y T25/T26 (`MrPurple666/purple-turnip`)
- StevenMXZ v27.0.0‑R1 (`StevenMXZ/Adreno-Tools-Drivers`)
- Balemuni Apex (`Balemuni/Balemunis-Aurora`)
- ARMSX2 Turnip (`bmdhacks/armsx2-turnip`)
- K11MCH1 v26.0.0 R8 (`K11MCH1/AdrenoToolsDrivers`), de respaldo

Uso previsto: Switch → el que recomiende Eden, o T30; PS2 → ARMSX2 Turnip; PC → T25/T26;
Vita → T30; GameCube/Wii y 3DS → driver del sistema.

### Etapa 4 · PS2 (prioridad alta)

- **ARMSX2** desde su GitHub (la de Play Store no trae generación de fotogramas). Versión de
  referencia: 2.6.6.8.4 o más nueva.
- Carpeta de datos `ARMSX2/` en la raíz del almacenamiento interno. BIOS en `ROMs/BIOS`, juegos en
  `ROMs/ps2` en formato CHD.
- Vulkan con ARMSX2 Turnip; escalado 3x global; memory card en modo carpeta; parches de panorámico y
  60 fps por juego.
- Atajos: menú = atrás; L3+R guardar; L3+L1 cargar; L3+R2 avance rápido; L3+Y cerrar.
- Importar `Lossless.dll` desde la carpeta de Steam del usuario y configurar la generación adaptativa.
- Modo de la consola en **Performance** para PS2.
- **NetherSX2** solo como respaldo si un juego falla (una guía todavía lo prefiere).
- Prueba del usuario: un juego con y sin generación de fotogramas.

### Etapa 5 · Juegos de PC

- **GameNative** (GitHub `utkarshdalal/GameNative`, 1.4.0 o más nueva) como base.
- **GameHub** oficial para comparar FPS.
- **DroidDeck** (0.7.x, noviembre de 2026) como tercera opción: ejecuta el cliente de Steam real en
  local. Pide Adreno 730 o superior (la consola tiene 740) y quitar el límite de procesos hijo. Hay
  varios dominios falsos: usar solo el repositorio de GitHub del proyecto, y verificarlo.
- Drivers T25/T26 o T30.
- Prueba: el mismo juego en las apps instaladas, anotando FPS, temperatura y estabilidad. El usuario
  elige cuál se queda.

### Etapa 6 · Frontends

- Instalar **Cocoon** (`inssekt/CocoonFE`) y **Daijishō**, y ES‑DE si el usuario lo compra, con la
  misma biblioteca.
- Abrir el frontend **antes** de copiar juegos para que cree las carpetas en la microSD.
- Con ES‑DE: su carpeta en la memoria interna.
- El usuario elige uno. Después, en Heaven: X sobre el botón de jugar › elegir ese frontend.

#### Copiar la biblioteca de juegos

1. Averiguar la ruta real de la tarjeta: `adb shell sm list-volumes public` (será algo como
   `/storage/XXXX-XXXX`).
2. **Convertir en la G14 antes de copiar**, para ahorrar espacio: PS1, PS2, Dreamcast y Saturn a CHD
   con `chdman` (viene con MAME, de `mamedev.org`); GameCube y Wii a RVZ con Dolphin para Windows.
   Switch se queda en NSP/XCI. Conservar los originales hasta comprobar que la copia arranca.
3. **Bibliotecas grandes**: apagar la consola, pasar la tarjeta al lector de la G14 y copiar con
   `robocopy` (reanuda si se corta y deja registro). **Archivos sueltos**: `adb push`.
4. Antes de copiar, enseñar al usuario cuánto ocupa cada sistema y cuánto queda libre en la tarjeta.
5. Después de copiar, comparar número de archivos y tamaño total por carpeta.
6. Carátulas: usar el descargador que traiga el frontend elegido. Si pide cuenta (ScreenScraper u
   otra), la cuenta y la contraseña las pone el usuario.

### Etapa 7 · Resto de emuladores

- Switch: **Eden**; **Citron Neo** de respaldo. Las claves (`prod.keys`) y el firmware los aporta el
  usuario, volcados de su propia Switch; la sesión solo los coloca donde el emulador los pide.
- GameCube/Wii: **Dolphin** (Vulkan 3x, Ubershaders híbridos, compilar shaders antes).
- 3DS: **Azahar**. Vita: **Vita3K** con Turnip. PSP: **PPSSPP** (4x). PS1: **DuckStation**.
- **RetroArch AArch64** (Mupen64Plus, Flycast, Beetle Saturn, melonDS).
- Wii U: **Cemu** (experimental). Xbox original: **hakuX** 1.2.0 o más nueva.
- **Switch 2**: no instalar nada. A noviembre de 2026 no hay un emulador fiable para Android; el
  único fork fue archivado y los APK que circulan son un riesgo.

### Etapa 8 · Streaming

- En la G14: **Apollo** (pantalla virtual, HDR apagado) y **Tailscale**.
- En la Pocket: **Artemis** (1080p, 120 fps, 40 Mbps en casa, frame pacing Warp 2, Ultra Low Latency),
  y **Tailscale**.
- PS5: comparar **PS Remote Play** oficial, **chiaki‑ng** y **PXPlay** (de pago; su estado actual está
  **sin verificar**). El usuario elige.
- En la G14: límite de batería al 80 % y sin suspensión cuando esté enchufada.
- Probar Wake‑on‑LAN desde Artemis. La G14 no tiene Ethernet y despertar un portátil por Wi‑Fi no
  suele funcionar: **sin verificar**. Si no funciona, la alternativa es dejarla encendida y enchufada
  con la tapa abierta, o encenderla a mano.
- Pasos que solo puede hacer el usuario: iniciar sesión en Tailscale en los dos equipos (misma
  cuenta), escribir en Apollo el PIN que muestra Artemis, activar «Uso a distancia» en la PS5
  (Ajustes › Sistema › Uso a distancia) e iniciar sesión en PSN.
- Probar primero en casa por Wi‑Fi de 5 GHz o 6 GHz y después fuera de casa por Tailscale; anotar
  latencia y bitrate que aguanta cada caso.

#### Partidas guardadas (opcional, se decide con el usuario)

- **Syncthing‑Fork** en la Pocket y Syncthing en la G14, sincronizando solo las carpetas de partidas
  de cada emulador hacia una carpeta de copia en la G14. Nunca sincronizar ROMs ni shaders.
- Sin sincronización: copia manual de las carpetas de partidas a `/Backups` de la tarjeta al terminar.

### Etapa 9 · Ports nativos (sesión aparte)

- **Bloodborne‑Android** (`ducvd89/Bloodborne-Android`): probado en 8 Gen 2, unos 30–45 fps; no está
  confirmado que se pueda terminar.
- GTA IV, Black Ops 2 (T6 Requiem), Skate 3, Forza Horizon 2: **sin verificar** que existan ports
  fiables; buscarlos y confirmarlos antes de contar con ellos.
- Minecraft Java con mods (Zalith Launcher 2) y Fortnite desde Play Store.

### Etapa 10 · Dejar Heaven a punto

Con todo instalado, y con el usuario mirando la consola (o por scrcpy):

1. Botón de jugar: X sobre el botón › elegir el frontend que se quedó.
2. Todas las apps: ocultar lo que no se usa (X sobre la app › Ocultar) y revisar que cada emulador
   cayó en la categoría «Emuladores»; corregir con X › Categoría.
3. Fijar en el carrusel lo que el usuario usa a diario (SELECT sobre la app).
4. Música de fondo: copiar las canciones del usuario a `/Musica` de la tarjeta y elegirlas en
   Custom › Sonido.
5. BOOST: comprobar que aparece la fila de ClusterTune ahora que está instalado.
6. Volver a detectar el modo de los gatillos (ventana de gatillos › X para olvidar › seguir los tres
   pasos) y, si la parte 6.1 dio resultado, anotar la clave del estilo de mando.
7. Exportar la copia completa (Custom › Copia de seguridad) a `/Backups`.

---

## 6. Investigación para Heaven (hazla en la etapa 1 o 2)

Heaven necesita saber cómo cambia la consola ciertos modos. Con ADB se puede averiguar en minutos lo
que desde la nube no he podido. Guarda todos los resultados en `C:\Pocket\hallazgos-heaven.md` y
pásaselos al usuario para que los lleve a la sesión de la nube.

### 6.1 Estilo de mando (Retro / Xbox) — lo más importante

El mosaico del panel se llama `controllerStyle` y, según el usuario, también muestra un estado
«Disconnected». El detector de Heaven no ha conseguido aprenderlo.

1. Foto de antes:

   ```bash
   adb shell "settings list system; settings list secure; settings list global; getprop" > antes.txt
   ```

2. El usuario cambia el mosaico de estilo de mando una vez.
3. Foto de después, y comparar:

   ```bash
   adb shell "settings list system; settings list secure; settings list global; getprop" > despues.txt
   ```

4. Repetir para cada estado (Retro, Xbox, Disconnected) y anotar qué clave y qué valor tiene cada uno.
5. Si no cambia ningún ajuste ni propiedad, mirar qué hace el sistema:

   ```bash
   adb logcat -c
   ```

   ```bash
   adb logcat -d > mando.log
   ```

   (el usuario cambia el mosaico entre los dos comandos). Buscar líneas de `com.rp.settings`,
   `SystemUI`, `gamepad`, `joystick`, `abxy`.
6. Probar a aplicarlo a mano y confirmar con el usuario que el mando cambia de verdad:

   ```bash
   adb shell settings put system CLAVE VALOR
   ```

   o bien `adb shell setprop CLAVE VALOR`. Anotar si basta con eso o si hace falta algo más
   (un broadcast, reiniciar un servicio, escribir en un nodo de `/sys`).
7. Candidatos vistos en el diagnóstico: `persist.sys.gamepad.type` (0), `persist.sys.handle.mode`
   (0), `no_create_gamepad_button_layout` (0).

### 6.2 Modo de los gatillos (Analógico / Digital / Ambos)

Mismo procedimiento que 6.1 con el mosaico `triggerInputMode`. Heaven ya aprendió dos de los tres
estados; hace falta la clave y los **tres** valores.

### 6.3 FPS

```bash
adb shell dumpsys SurfaceFlinger --timestats -enable -clear
```

```bash
adb shell dumpsys SurfaceFlinger --timestats -dump
```

Con un juego en marcha, ejecutar el segundo comando dos veces con un segundo de diferencia y guardar
la salida completa. Heaven busca la línea `totalFrames = N` y calcula la diferencia; hay que
confirmar que esa línea existe, cómo se llama exactamente y si el número cuadra con los FPS reales.

### 6.4 Ventilador

- Rango real de `fan_speed`: poner el modo personalizado, mover el control de la consola a mínimo y
  máximo y leer `adb shell settings get system fan_speed` en cada extremo.
- Confirmar qué hacen `fan_mode` 0 y 1 (si existen en esta consola).

### 6.5 Otros mosaicos de la consola

Para una futura ventana de Heaven: con el mismo método de antes/después, anotar clave y valores de
`charge_limit` (¿`percent_80_charge_limit` 0/1?) y `peak_refresh_rate` (60/120).

### 6.6 Comprobaciones de Heaven

- Estadísticas en juego: en un juego, mantener **L1 + R1 + SELECT**. ¿Aparece la tira? ¿Sale un
  número en FPS o un guion?
- Puntero: ¿A toca al instante o con retraso? (con retraso significa que está usando la vía de
  respaldo).
- Si algo falla, guardar `adb logcat -d | findstr /i "palanca AndroidRuntime"` justo después.

---

## 7. Cómo ordenar el almacenamiento

### microSD (512 GB): lo grande y lo que no necesita velocidad

```text
/ROMs/
    BIOS/
    ps2/  gc/  wii/  wiiu/  switch/  3ds/  nds/  psp/  psx/  psvita/
    n64/  dreamcast/  saturn/  snes/  nes/  gb/  gbc/  gba/  genesis/  arcade/  xbox/
/Media/        carátulas y vídeos del frontend, si el frontend lo permite en la tarjeta
/Drivers/      los zip de Turnip
/Musica/       canciones para el fondo de Heaven
/Backups/      copias de configuración (Heaven, emuladores)
```

### Memoria interna (128 GB): lo que necesita rapidez o que Android no deja mover

- Las apps y sus datos.
- Carpetas de datos de los emuladores (`ARMSX2/`, datos de Eden, Dolphin, etc.): shaders, memory
  cards, estados guardados.
- La carpeta de ES‑DE.
- Contenedores e instalaciones de GameNative, GameHub y DroidDeck (los juegos de PC rinden mejor en
  interno; si no caben, decidir juego a juego).
- Firmware y claves de Switch.

Regla: los juegos (ROMs) van a la tarjeta; todo lo que el emulador escribe mientras juegas va en
interno. Antes de copiar, enseñar al usuario cuánto ocupa cada sistema y cuánto queda libre.

---

## 8. Al terminar

1. `C:\Pocket\resumen.md`: apps instaladas con versión y origen, driver asignado a cada emulador,
   ajustes clave, y qué quedó pendiente.
2. `C:\Pocket\hallazgos-heaven.md`: resultados de la parte 6, con los comandos exactos que funcionaron.
3. Copia de seguridad de Heaven: Custom › Copia de seguridad › Exportar copia completa, guardada en
   `/Backups` de la tarjeta.
4. El usuario lleva `hallazgos-heaven.md` a la sesión de la nube para que Heaven integre el estilo de
   mando, los gatillos y los FPS con datos reales.

---

## 9. Problemas habituales

| Síntoma | Qué hacer |
|---|---|
| `adb devices` no muestra nada | Cambiar de cable (muchos USB‑C son solo de carga) y de puerto; modo USB en «Transferencia de archivos»; instalar el Google USB Driver |
| Sale `unauthorized` | Aceptar el aviso en la pantalla de la consola. Si no aparece: Opciones de desarrollador › Revocar autorizaciones de depuración USB, y reconectar |
| Sale `offline` | `adb kill-server`, reconectar el cable |
| La consola se bloquea a mitad de un paso | Activar «Pantalla activa mientras se carga» |
| Play Protect avisa al instalar un APK | Lo acepta el usuario en pantalla; es normal en apps de fuera de Play Store |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | La app instalada tiene otra firma. No desinstalar sin preguntar (se pierden los datos) |
| Un emulador no ve la tarjeta | Darle acceso a la carpeta desde su propio selector de carpetas; Android 13 no deja a las apps entrar en `Android/data` de otras |
| Falta espacio en interno | Enseñar al usuario qué ocupa más (`adb shell du -sh /sdcard/*`) y decidir con él qué pasa a la tarjeta |
| Heaven deja de responder al mando o el puntero desaparece | Reactivar el servicio de accesibilidad de Heaven y comprobar que no tiene restricción de batería |
| Algo de Heaven falla | Custom › Sistema › Exportar diagnóstico, y guardar el archivo junto a `hallazgos-heaven.md` |


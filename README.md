# Ride Decider — esqueleto del proyecto

App Android/Kotlin que lee simultáneamente las solicitudes de viaje de las apps que
tengas abiertas en pantalla dividida (Uber, Cabify, DiDi, inDrive, o cualquier otra),
sin depender de parsers específicos por app, calcula ganancia neta real (después de
comisión y costo propio por km), y muestra un semáforo flotante (verde/amarillo/rojo)
sobre cada porción de pantalla para decidir rápido.

## Cartel flotante: tamaño, contenido y auto-ocultado

El cartel es 3 veces más grande que la versión inicial (36sp, antes 12sp). Ahora
muestra siempre un cartel **gris "Esperando datos…"** para cada app detectada en
pantalla aunque todavía no haya ninguna oferta visible — así confirmás que esa región
sí está siendo leída, en vez de no ver nada y no saber si falta algo.

Qué campos aparecen en el cartel se configura en Settings (monto, distancia, neto por
km, neto por hora, score) — pero **el cálculo del semáforo (color) siempre usa todos
los datos disponibles**, apagar un campo del cartel nunca cambia qué tan conveniente
se considera un viaje, solo qué número se ve escrito. El score ahora se muestra como
"Conviene: N/100" en vez de solo un número suelto.

También en Settings: cuántos segundos queda el cartel en pantalla antes de ocultarse
solo (por defecto 10; poné 0 para que nunca se oculte). **El auto-ocultado solo aplica
al cartel con una oferta de viaje real detectada** (monto/km/score) — es el dato que
importa leer rápido y decidir. El cartel gris "Esperando datos…" (todavía no hay
ninguna oferta en esa app) NO se autooculta nunca: queda fijo como confirmación de que
esa app sigue siendo monitoreada, hasta que aparezca una oferta real (ahí se muestra
esa info 10 segundos) o la app deje de estar en pantalla.

Solo se ven carteles de las apps que elegiste en "Elegir apps a abrir" — no de
cualquier ventana visible en pantalla.

Si solo ves el cartel de una de las apps abiertas en pantalla dividida, puede ser que
el sistema no esté avisándole al Accessibility Service cuándo cambia el conjunto de
ventanas visibles (`typeWindowsChanged`, ya agregado) — por las dudas, además de
reaccionar a eventos, el servicio reescanea todas las ventanas cada 1.5 segundos como
respaldo.

## Elegir qué apps abrir

Ya no hay una lista fija de 4 apps en el código. Desde el botón "3. Elegir apps a
abrir" (`AppPickerActivity`) elegís hasta 4 apps de **todas** las instaladas en el
dispositivo — el orden en que las tildás es el orden en que se van a acomodar en
pantalla. Al lanzarlas (botón "5."), `MainActivity.computeBounds()` calcula los
rectángulos para que llenen TODA la pantalla sin importar si elegiste 1, 2, 3 o 4
(no es un 2x2 fijo: con 3 apps, por ejemplo, una ocupa una franja completa y las
otras dos se reparten el resto).

**Cerrar las apps / cerrar Ride Decider:** el botón "Cerrar las apps elegidas" usa
`killBackgroundProcesses()`, que es lo único que Android deja hacer a una app normal
sin privilegios especiales — funciona bien si esa app ya pasó a segundo plano, pero
**no puede forzar el cierre de una app que esté activa en primer plano** (restricción
de seguridad del sistema, no hay forma de evitarla desde una app común). Si no se
cierra sola, Android te pide que la deslices vos desde "Recientes". El botón "Cerrar
Ride Decider" cierra el panel flotante y la pantalla principal; el Accessibility
Service sigue corriendo en segundo plano a propósito (para apagarlo del todo hay que
desactivarlo en Ajustes de Accesibilidad, igual que siempre).

## Qué se guarda de verdad (y qué no)

Tanto la selección de apps (`AppSelectionStore`) como la configuración de Settings —
pesos, comisiones, costo por km (`AppConfigHolder`) — se guardan en `SharedPreferences`,
que es almacenamiento propio de la app en el dispositivo. Eso significa que **sobrevive**:
cerrar la app, reiniciar el celular, e instalar una actualización encima de la versión
anterior (una actualización no borra los datos de la app, solo una desinstalación
completa sí). La pantalla de Settings además se precarga con lo último que guardaste,
no con los valores por defecto cada vez.

## Arquitectura (actualizada): lectura universal, no por app

Ya no hay un parser por plataforma (`UberParser`, `CabifyParser`, etc.). Hay un único
**`GenericOfferParser`** que no sabe nada de ninguna app en particular: recibe el texto
visible de UNA ventana (una app, en su porción de pantalla) y busca patrones genéricos
— un monto ($), una distancia (km), un tiempo (min) — para decidir si "esto parece una
oferta de viaje", sin importar cuál app sea ni cómo esté diseñada su pantalla.

Cómo funciona el flujo completo:

1. **`RideAccessibilityService`** ya no filtra por packageName (ver
   `accessibility_service_config.xml`, sin restricción de apps). En cada evento,
   recorre **todas las ventanas visibles** (`windows`), que es la lista de apps que
   tenés abiertas simultáneamente en pantalla dividida — da igual si son 2, 3 o 4.
2. Para cada ventana, identifica el **packageName real** y resuelve el **nombre
   visible de la app dinámicamente con `PackageManager`** (no con una lista fija) —
   así se "identifica qué app se abrió" automáticamente, sin tocar código.
3. Corre `GenericOfferParser` sobre el árbol de esa ventana. Si no encuentra nada
   (árbol bloqueado / vistas custom), guarda los **bounds de esa ventana** y cae al
   OCR (`ScreenOcrHelper`), que recorta solo esa región de la pantalla — tampoco el
   OCR necesita saber qué app es, solo qué parte de la pantalla leer.
4. El resultado (`RideOffer`) se guarda en `RideOfferStore`, con el packageName como
   clave (no un enum fijo de 4 apps).
5. **`DecisionEngine`** calcula ganancia neta (tarifa − comisión − costo propio por
   km), $/km y $/hora netos, y un score 0-100 → semáforo.
6. **`OverlayService`** dibuja un badge de color **sobre cada región de pantalla
   detectada** (usando los bounds guardados en el paso 3), marcando con ★ cuál es la
   mejor opción en este momento.

Qué significa esto para cuando una app se actualice o cambie de diseño: como el
parser no depende de resource-ids ni de la estructura visual de ninguna app, sigue
funcionando mientras la app siga mostrando el monto, la distancia o el tiempo como
texto en pantalla (que es casi siempre). Lo único que puede necesitar ajuste con el
tiempo son los **regex** de `GenericOfferParser.kt` (formato de moneda, separador
decimal, palabras clave de contexto) — están todos juntos en un solo archivo.

Comisión por app: como ya no hay un enum fijo, `CommissionConfig` tiene una comisión
**por defecto** (se aplica a cualquier app nueva que detecte) más **overrides
opcionales por packageName** para las apps que ya conocés y cuya comisión real
querés afinar (configurable en la pantalla de Settings, un override por línea).

## Autoactualización (la app se revisa sola contra GitHub)

Cada push a `main` compila el APK Y publica un GitHub Release (`build-<N>`) con ese
APK como asset. Al abrir la app (`MainActivity`), `UpdateChecker.kt` consulta
`GET /repos/lord-amd/ride-decider/releases/latest` (sin token: por eso el repo tiene
que ser **público**), compara el número de build contra `BuildConfig.BUILD_NUMBER`
(el que quedó grabado en el APK instalado al compilarlo), y si hay uno más nuevo te
pregunta si querés descargarlo. Si confirmás, lo baja con el `DownloadManager` del
sistema y abre el instalador de Android automáticamente.

Lo que Android NO permite, por seguridad, es que una app instalada por fuera de Play
Store se reemplace a sí misma en silencio sin que vos confirmes con un toque en el
instalador — eso aplica a cualquier app sideloaded, no es una limitación de este
proyecto. Lo que sí queda resuelto: nunca más tenés que ir a buscar el APK a mano,
la app te avisa y te lo deja descargado, listo para instalar con un toque.

Primera vez que instales una actualización, Android te va a pedir el permiso
"Instalar apps desconocidas" para Ride Decider — es el mismo permiso de siempre al
sideloadear, se pide una sola vez.

## Compilación automática con GitHub Actions

Se agregó `.github/workflows/build.yml`: cada vez que hagas push a `main` (o abras un
PR, o lo dispares a mano desde la pestaña "Actions"), GitHub compila el APK debug por
vos y lo deja descargable como "artifact" del workflow — no necesitás Android Studio
ni Gradle instalado localmente para tener un APK actualizado.

Cómo usarlo:

1. Creá un repo en GitHub y subí esta carpeta:
   ```
   git init
   git add .
   git commit -m "Ride Decider: esqueleto inicial"
   git branch -M main
   git remote add origin <URL de tu repo>
   git push -u origin main
   ```
2. En GitHub, pestaña **Actions** del repo → el workflow "Build APK" corre solo.
3. Cuando termine (ícono verde ✓), entrá al run → sección **Artifacts** → descargá
   `ride-decider-debug-apk` → es un .zip con el `.apk` adentro, instalalo en tu
   dispositivo (necesitás permitir "orígenes desconocidos").

Nota técnica: este esqueleto no versiona `gradlew`/`gradle-wrapper.jar` (es un binario
que no puedo generar desde acá sin un Gradle real instalado), así que el workflow
instala Gradle directamente en el runner con `gradle/actions/setup-gradle`. Si después
abrís el proyecto en Android Studio, él mismo genera el wrapper local automáticamente
la primera vez que sincroniza.

## Lo que tenés que verificar vos (no lo puedo hacer sin tu dispositivo)

1. **Formato real de los textos** que muestra cada app en pantalla (monto, km, min).
   Los regex de `GenericOfferParser.kt` cubren los formatos más comunes ($450,
   $1.234,50, 4,2 km, 12 min) pero puede que alguna app use algo distinto — mirá el
   campo `rawText` de cada `RideOffer` en Logcat para ajustar si hace falta.
2. **Soporte de pantalla dividida en 3-4 apps en tu dispositivo.** El overlay ya
   soporta cualquier cantidad de regiones (no asume 4 fijas), pero que Android
   realmente te deje ver 3-4 apps a la vez depende de tu equipo (tablet, Samsung DeX,
   o Android 12L+). `MainActivity` intenta forzar bounds con `ActivityOptions`, pero
   en un celular común sin freeform, cada app se abre en pantalla completa igual — en
   ese caso el semáforo sigue funcionando sobre la app que tengas en foco.

## Riesgos a tener en cuenta (no técnicos, pero importantes)

- Leer y automatizar decisiones sobre apps de viajes probablemente viola sus Términos
  de Servicio. Es un riesgo de tu cuenta de conductor, no algo que el código evite.
- Esto **no se puede publicar en Google Play**: Play Store prohíbe usar el
  Accessibility Service para fines distintos a accesibilidad real, y menos aún leer
  TODAS las apps en pantalla sin restricción. Es un APK para instalar manualmente
  (sideload) en tu propio equipo.

## Cómo seguir

1. Subí el proyecto a GitHub (ver arriba) y descargá el primer APK compilado.
2. Instalalo en tu dispositivo, activá el Accessibility Service y el permiso de
   superposición desde la pantalla principal de la app.
3. Abrí 2-3 apps de viajes a la vez (en modo que tu equipo soporte) y mirá en Logcat
   el `rawText` que va capturando `GenericOfferParser` para confirmar que detecta bien
   monto/distancia/tiempo en cada una.
4. Ajustá pesos y comisiones en Settings con viajes reales para calibrar el semáforo.
5. Cablear el fallback de OCR real (`ScreenOcrHelper`, pide permiso de
   `MediaProjection` una vez) para las apps que bloqueen el árbol de accesibilidad.

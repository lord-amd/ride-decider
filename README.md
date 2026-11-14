# Ride Decider — esqueleto del proyecto

App Android/Kotlin que lee simultáneamente las solicitudes de viaje de las apps que
tengas abiertas en pantalla dividida (Uber, Cabify, DiDi, inDrive, o cualquier otra),
sin depender de parsers específicos por app, calcula ganancia neta real (después de
comisión y costo propio por km), y muestra un semáforo flotante (verde/amarillo/rojo)
sobre cada porción de pantalla para decidir rápido.

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

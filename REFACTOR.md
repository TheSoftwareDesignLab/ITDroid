# ITDroid Refactorización y modernización

Cambios realizados al modernizar ITDroid y reemplazar su motor de exploración, cada uno con su justificación.

Resumen: ITDroid quedó actualizado a Java 21, se reemplazó el traductor IBM Watson (deprecado) por un LLM local, se actualizaron los binarios externos, se corrigieron bugs, y se reemplazó el motor de exploración cerrado (RIP, 2017), que se colgaba en Android moderno, por uno propio basado en `adb` que no necesita root. El flujo completo (decode, traducir, reconstruir, explorar, detectar IPFs, reporte) se validó de punta a punta.

Para entender cómo está armado todo ver ARCHITECTURE.md. Para cómo ejecutar ver README.md.

# Novedades destacadas

- Traductor LLM local: reemplaza IBM Watson; HTTP OpenAI-compatible (Ollama, llama.cpp, LM Studio).
- Traducción de `<plurals>` y `<string-array>`, incluso si viven en `plurals.xml` o `arrays.xml` aparte.
- Traducción de textos hardcodeados: detecta literales UI en el smali, los traduce y arma un APK por idioma.
- Archivo de config único: un `itdroid.config.properties` reemplaza los 8 argumentos de la CLI y elige el motor de traducción.
- 8º argumento CLI (LLM): acepta un modelo Ollama (hace `ollama pull`) o un comando (`llama-server ...`); arranca o verifica el server.
- Reinstala el APK original al final: el dispositivo siempre termina con la app original.
- Captura estable (`stableDump`): exploraciones reproducibles, emparejamiento e IPFs consistentes.
- Motor de exploración propio sin root: reemplaza RIP (2017); locale por-app más DFS por `adb`.
- Clean reset entre idiomas: `pm clear` sin root antes de cada idioma, sin estado residual.

# 1. Toolchain y build (pom.xml)

- Un solo `maven-compiler-plugin`, Java 21 (antes había dos plugins duplicados, uno con `source 1.7`). El duplicado generaba warning y el 1.7 rompería en JDK 20+.
- `maven-compiler-plugin` 3.5.1 a 3.13.0 y `maven-shade-plugin` 2.3 a 3.6.0. Las versiones viejas (2014-2016) son incompatibles con JDK nuevos.
- Encoding UTF-8 fijado (el build dependía de la plataforma).
- Eliminadas 8 dependencias de Eclipse (`org.eclipse.core.*`, `equinox.*`, `jdt.core`, `osgi`): no tenían un solo `import` en el código. El jar bajó de 24 MB a 6.4 MB.
- `commons-io` normalizado al artefacto canónico (`commons-io:commons-io:2.16.1`); venía repaquetado raro y viejo (de 2013).
- Eliminadas `com.ibm.watson:java-sdk` y `io.github.cdimascio:java-dotenv` (el traductor IBM Watson se reemplazó por el LLM local).
- Eliminadas `antlr 3.5.2` y `dexlib2 2.2.3`, las deps congeladas: quedaron sin uso al reemplazar el análisis smali AST por uno basado en regex. El jar bajó de 6.4 MB a 1.2 MB.

# 2. Traducción: IBM Watson a LLM local

Problema: IBM Watson Language Translator fue deprecado/retirado por IBM; no se pueden crear instancias nuevas. El código dependía de él y de un `.env` con credenciales.

Solución: nuevo traductor contra un LLM local por endpoint OpenAI-compatible.

- Añadido `translator/LLMTranslator.java`: cliente HTTP (usa `java.net.http` nativo, sin dependencias nuevas). Traduce por lotes en JSON preservando placeholders (`%1$s`, `%d`) por instrucción al modelo. Agnóstico al runtime: Ollama, llama.cpp o LM Studio cambiando solo la config.
- Borrado `IBM/IBMTranslator.java` y todo el paquete `IBM` (dependía del servicio deprecado).
- Borrado `translator/GoogleTranslator.java` (era un stub vacío).
- Añadido `.env.example` (documenta `ITDROID_LLM_ENDPOINT`, `_MODEL`, `_API_KEY`, `_TIMEOUT`, `_TEMPERATURE`).
- Modificado `ITDroid.java` para usar `LLMTranslator` en vez de `IBMTranslator`.

Validado con llama.cpp (Qwen2.5-3B) y Ollama (mistral:7b): ambos traducen preservando placeholders.

Auto-detección del idioma de origen (`LLMTranslator.detectSourceLanguage`): ITDroid le pregunta al LLM en qué idioma están las cadenas de `values/strings.xml` y usa ese código como origen, con fallback a `defaultLng`. Un guard evita traducir el idioma origen a sí mismo. Se eligió detectar por el contenido de la app y no por el locale del dispositivo.

Soporte de `<plurals>` y `<string-array>` (`LLMTranslator.translate`): antes solo se traducían los `<string>`, así que recursos como `<plurals name="expenses_count">` nunca se traducían. Ahora se reconstruye el contenedor traduciendo cada `<item>` y preservando atributos (`quantity`) y placeholders.

# Arranque automático del servidor LLM

Problema: si el servidor LLM no estaba levantado, la traducción reventaba a mitad del proceso con un `ConnectException` poco claro.

Solución: nuevo `helper/LLMServerManager.java`, invocado al inicio de `runITDroid`. Comportamiento:

- El endpoint ya responde: lo reutiliza (no arranca nada ni lo apaga; respeta un server del usuario).
- El endpoint está caído y se pasó el comando de arranque (8º argumento): arranca el server vía shell, espera a que responda (`/v1/models` o `/health` = 200, hasta 180 s), traduce, y al terminar mata el árbol de procesos (shutdown hook). Su salida va a `<salida>/llm-server.log`.
- El endpoint está caído y no se pasó comando: aborta de inmediato con un mensaje accionable.

El 8º argumento acepta dos formas:

- Nombre de modelo Ollama (token suelto, ej. `qwen2.5:3b`): apunta el traductor a Ollama, arranca `ollama serve` si hace falta y hace `ollama pull <modelo>` (lo descarga si no lo tienes). Sobrescribe `ITDROID_LLM_ENDPOINT`/`_MODEL` vía system properties.
- Comando de arranque (con espacios, ej. `"llama-server -m modelo.gguf --port 8080"`): se ejecuta vía shell y se espera al endpoint de `.env` (el puerto debe coincidir con `ITDROID_LLM_ENDPOINT`).

Validado: modo modelo (Ollama serve+pull+uso de `mistral:7b`), arranque por comando con readiness y tree-kill, y reuso de un Ollama ya corriendo sin matarlo.

# El dispositivo queda siempre con la app original

Problema: al terminar, el dispositivo quedaba con la última versión traducida instalada. El archivo APK original ya se preservaba, pero el dispositivo no.

Solución: como último paso de `runITDroid`, ITDroid reinstala el APK original (el archivo pasado como 1er argumento) en el dispositivo (`AdbExplorer.installApk`: desinstala y `adb install -t` del original, tolerando el cambio de firma). Es best-effort: un fallo aquí solo emite un warning.

# 3. Motor de exploración: RIP (cerrado, 2017) a AdbExplorer (propio, sin root)

Problema (diagnosticado con `jstack`): los jars cerrados `RIPi18n.jar` y `RIPRRi18n.jar` hacen deadlock en Android moderno; su `BufferedReader.readLine()` sobre un subproceso `adb` nunca retorna. Ocurre con root y sin root (probado en API 33 y en una imagen rooteable API 30). Además dependían de `adb root` + `setprop persist.sys.locale`, que falla en imágenes Play Store y dispositivos físicos.

Solución: explorador propio basado en `adb` estándar.

- Añadido `explorer/AdbExplorer.java`: instala el APK, fija el idioma por-app (`cmd locale set-app-locales`, Android 13+, sin root), explora con DFS in-place (`uiautomator dump` + `input tap` + botón atrás), captura jerarquía y screenshots, y emite el mismo `result.json` que parsea `LayoutGraph`. Firma de estado estructural (por `resource-id`, ignora el texto).
- Añadido `helper/ExplorationHelper.java` (reemplaza a `RIPHelper`).
- Añadido `helper/ExplorationException.java` (reemplaza a `RipException`).
- Borrado `helper/RIPHelper.java` (lanzaba los jars RIP; incluía código muerto).
- Borrado `helper/RipException.java` (renombrado).
- Borrado `helper/EmulatorHelper.java` (código muerto que usaba locale por root).
- Borrados de `extra/`: `RIPi18n.jar`, `RIPRRi18n.jar`, `RIPRR.jar`, `resemble-java-*.jar` y `whileCommand`.
- Modificado `ITDroid.java` (llamadas y catch a los nombres nuevos).

Ventaja clave: el cambio de idioma por-app no requiere root, funciona en emulador (incluida imagen Play Store) y en dispositivos físicos.

# 4. Corrección de bugs de lógica

- `model/LayoutGraph.java`: el bucle de transiciones arrancaba en `i=1` y perdía una; ahora itera las claves presentes.
- `model/AndroidNode.java`: `case ENABLED` ponía siempre `true`; ahora lee el valor del atributo.
- `ITDroid.java`: nombre de idioma por defecto hardcodeado `"English"`, ahora se deriva. Parseo de rutas dependiente de Windows (`replaceAll("/", File.separator)` podía lanzar excepción), ahora usa `File.getName()` + `replace` literal. Imports muertos eliminados.

Nota: `LayoutGraphComparision.pairStateNodes` se dejó intacto a propósito; usa emparejamiento posicional, no es un bug. Cambiarlo a similitud de texto entre idiomas sería peor.

# Detección de strings hardcodeados reescrita (soporte Jetpack Compose)

Problema: el detector reportaba 0 strings hardcodeados en apps Compose. Dos causas: solo escaneaba `temp/smali`, pero las apps modernas son multidex y el código va en `smali_classes2..N`; y las heurísticas AST estaban hechas para patrones legacy (`findViewById`/`setText`), no para Compose.

Solución: se reescribió como `HardcodedStringFinder` (antes `ASTHelper`):

- Escanea todos los directorios `smali*` del paquete de la app.
- Busca instrucciones `const-string` y filtra el ruido (claves de Compose, `<anonymous>`, fragmentos de `toString`, descriptores `L...;`, URLs, identificadores camelCase, números/fechas).
- Resultado en la app de prueba: de 0 a 30 strings hardcodeados reales detectados.

Limpieza derivada: al quitar el análisis AST, todo el subsistema viejo de parsing smali quedó sin uso y se eliminó: `ASTHelper`, `TreeVisitorInstance`, y los paquetes generados `uniandes.tsdl.antlr`, `uniandes.tsdl.jflex` y `uniandes.tsdl.smali`. Con ello se eliminaron del pom las deps `antlr 3.5.2` + `dexlib2 2.2.3` y el jar pasó de 6.4 MB a 1.2 MB.

# Clean reset entre idiomas

`AdbExplorer` hace un clean reset (`adb shell pm clear <pkg>`, sin root) antes de explorar cada idioma y en cada reinicio de actividades: borra datos, caché y detiene la app, y re-aplica el locale (porque `pm clear` también lo borra). Así no queda información residual entre evaluaciones de distintos idiomas.

# Captura estable de estados, emparejamiento consistente

Problema: la detección de IPFs era no-determinista. Para comparar dos idiomas, ITDroid empareja los estados del grafo inglés con los del español; si las exploraciones recorrían pantallas distintas, los estados no emparejaban y salían 0 IPFs. Causa: tras el arranque en frío, una app Compose muestra brevemente una pantalla a medio renderizar, y capturar ese instante producía un estado raíz distinto en cada idioma.

Solución: `AdbExplorer.stableDump()` vuelca la jerarquía repetidamente hasta que la firma estructural es igual en dos volcados consecutivos (la UI dejó de cambiar), con fallback acotado. Como la firma ignora el texto, las animaciones no la afectan. Resultado: ambos idiomas arrancan desde la misma pantalla ya renderizada. Validado: dos runs seguidos dieron 4 estados / 9 transiciones por idioma, 0 estados sin emparejar, 25 IPFs (antes: 0 y 14 en runs distintos). Coste: unos segundos extra por estado.

# Traducción de strings hardcodeados (APK por idioma)

ITDroid también traduce los textos hardcodeados (no solo `strings.xml`). Como el código tiene una sola versión de cada literal, esto requiere un APK por idioma. El flujo:

1. `HardcodedStringFinder.collectUiStrings` recolecta los literales UI hardcodeados.
2. `LLMTranslator.translateUiLabels` los traduce dejando intactos nombres propios, emails, URLs, códigos, placeholders y datos.
3. `SmaliPatcher` reemplaza los `const-string` en el smali (escapando no-ASCII a `\uXXXX`).
4. Se reconstruye y firma un APK por idioma (`<pkg>-<lang>-aligned-debugSigned.apk`) y se explora.
5. Se escribe un log revisable `hardcoded_<lang>.csv` (original;traducido;changed).

Validado e2e (dictoapp es a fr): 20 detectados, 16 parcheados, APK francés reconstruido OK, log generado. Las etiquetas UI se traducen ("Settings" a "Paramètres") y los placeholders se preservan ("%.2f").

Riesgo y mitigación: el detector es heurístico; algún literal podría ser código o dato y no UI. Se filtran automáticamente referencias a métodos, identificadores snake_case y patrones de fecha, y el `hardcoded_<lang>.csv` permite revisar el resto. El APK original nunca se parchea.

# 5. Binarios externos (extra/)

- `apktool.jar` 2.4.1 a 2.12.1. La 2.4.1 (2019) falla con APKs modernos. Se mantuvo en la rama 2.x (no 3.x) para que el smali generado siga siendo compatible.
- `uber-apk-signer.jar` 1.0.0 a 1.3.0. Soporta esquemas de firma nuevos.

Ambos verificados corriendo en JDK 25.

# 6. Otros

# Archivo de configuración único

Problema: ejecutar ITDroid requería recordar y escribir 8 argumentos posicionales.

Solución: un archivo `itdroid.config.properties` (KEY=VALUE) que se pasa como único argumento. Lo carga la clase `helper/RunConfig`. Contiene todos los parámetros de la corrida y el bloque `translation.*`, que deja elegir el motor sin tocar el `.env`:

- `ollama`: Ollama local. `model` + `autoStart=<modelo>` (hace `serve`+`pull`).
- `llamacpp`: llama.cpp local. `endpoint` + `autoStart` (comando de arranque).
- `api`: endpoint remoto OpenAI-compatible. `endpoint`, `model`, `apiKey` (token).

Los valores se inyectan como system properties `ITDROID_LLM_*` (máxima precedencia sobre `.env`). Para `engine=api`, `LLMServerManager` omite el health-check local y confía en la config. Se versiona solo `itdroid.config.example.properties`; el `itdroid.config.properties` real va en `.gitignore` (puede contener el token). La forma posicional de 7/8 argumentos sigue funcionando.

# Reglas reforzadas de no traducir

Ambos prompts del LLM (recursos `<string>` y textos hardcodeados) comparten la regla `DO_NOT_TRANSLATE`, que obliga a copiar verbatim: nombres propios, marcas, usuarios y handles, correos, URLs, paths, teléfonos, números sueltos, fechas, montos, códigos ISO, identificadores y placeholders.

Otros ajustes menores: comentario obsoleto de `settings.properties` actualizado; `.gitignore` con `temp/` y `output/`; correcciones en `README.md` (nombre del jar, número de argumentos, documentación del LLM y dispositivos).

# 7. Validación end-to-end

App de prueba (MiniWallet), emulador Test4 (API 33, Play Store, sin root), en unos 6 minutos: decode (apktool 2.12.1), traducción LLM (18/18), reconstrucción y firma, explorar inglés (4 estados), explorar español (4 estados), comparar, 0 estados sin emparejar, 19 IPFs detectados, `report.json`.

Los 19 IPFs son nodos cuyas relaciones espaciales cambian por el texto en español (pierden la relación LEFT/RIGHT al desplazarse), exactamente el propósito de la herramienta.

# 8. Limitaciones y trabajo futuro (no bloqueante)

- `State.compareTo` aún empareja estados por distancia de texto (funciona por umbral, pero un emparejamiento estructural sería más robusto).
- Calidad de traducción: un modelo 3B comete deslices ocasionales (traduce nombres propios). Un modelo 7B o un ajuste de prompt lo mejora.
- `MAX_STATES` (en `ExplorationHelper`) limita la cobertura por app; se puede subir.

# 9. Cómo ejecutar

1. Instalar un LLM local y configurarlo en `.env` (copia de `.env.example`).
2. Tener un emulador/dispositivo Android 13+ (API 33+) conectado por `adb` (uno solo).
3. `mvn clean package`.
4. `java -jar target/ITDroid-1.0.0.jar <APK> <paquete> extra/ . <alpha> <salida> <emulador> ["comando-de-arranque-del-LLM"]`. El 8º argumento es opcional: si se pasa, ITDroid arranca el servidor LLM y lo apaga al final; si se omite, el servidor debe estar ya corriendo.

Ver README.md para el detalle de argumentos.

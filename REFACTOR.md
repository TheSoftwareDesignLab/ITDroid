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
- Pruebas unitarias: JUnit 5 (`junit-jupiter`, solo en alcance de test) con `maven-surefire-plugin`. Los tests viven en `test/`, junto a `src/` y con los mismos paquetes, porque el árbol principal no usa la estructura estándar de Maven. `mvn test` los corre y `mvn package` también, antes de armar el jar. El proyecto no tenía ninguna prueba; el jar resultante no cambia (las dependencias de test no se empaquetan).

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

Nota: `LayoutGraphComparision.pairStateNodes` se dejó intacto en ese momento, con su emparejamiento posicional. La auditoría de noviembre de 2026 lo revisó y se sustituyó: ver "Comparador de layouts" en la sección 12.

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

- El emparejamiento posicional con distancia de texto (`State.compareTo`) solo se usa cuando el grafo del idioma no viene de un replay; con replay el emparejamiento es por id reproducido.
- Calidad de traducción: un modelo pequeño comete deslices ocasionales (traduce nombres propios). Un modelo mayor o un ajuste de prompt lo mejora.
- `exploration.maxStates` limita la cobertura por app; se puede subir en el archivo de config.
- Apps cuyas pantallas cambian entre arranques (diálogos de primer uso, avisos) pueden divergir en el replay; las divergencias quedan en `replay_report.json`.

# 9. Cómo ejecutar

1. Instalar un LLM local y configurarlo en `.env` (copia de `.env.example`).
2. Tener un emulador/dispositivo Android 13+ (API 33+) conectado por `adb` (uno solo).
3. `mvn clean package`.
4. `java -jar target/ITDroid-1.0.0.jar <APK> <paquete> extra/ . <alpha> <salida> <emulador> ["comando-de-arranque-del-LLM"]`. El 8º argumento es opcional: si se pasa, ITDroid arranca el servidor LLM y lo apaga al final; si se omite, el servidor debe estar ya corriendo.

Ver README.md para el detalle de argumentos.

# 10. Flujo de trabajo revisado tras la evaluación con apps reales

Tras evaluar ITDroid sobre 30 apps open source de F-Droid (15 completaron, 15 no) se rediseñó el flujo. Ver ARCHITECTURE.md para el detalle de cada fase.

# Preflight dentro de ITDroid

Antes se descubría a mitad de la corrida que una app no se podía procesar (solo ARM, TWA, crash del original, sin `strings.xml`, apktool sin poder reconstruir). Ahora `Preflight` y `AdbExplorer.checkLaunch` lo comprueban al principio: dispositivo API 33+ y ABIs, código nativo del APK, manifiesto (actividad lanzable, no TWA, minSdk), `strings.xml`, y una prueba de arranque del APK original con permisos concedidos. Cada rechazo lleva el motivo con prefijo `PREFLIGHT:`, queda en `report.json` y el proceso sale con código 1. Si apktool no reconstruye la app intacta, se aborta con ese motivo en vez de seguir sin APK.

# Inventario de strings con origen

Nueva clase `StringInventory`: recorre `res/values/*.xml` y el smali y produce `strings_inventory.json` con cada texto, su origen (archivo, y línea en el smali), si se traduce y por qué no. Reglas de exclusión unificadas para recursos y hardcodeados: `translatable="false"`, diccionario, referencias `@`/`?`, sin letras, solo números y símbolos, texto no humano, contenedores de datos por nombre (`*_values`, `*_keys`, `*_ids`, `*_urls`...). El texto se guarda como XML interno, así se conservan `<b>` y `<xliff:g>` que antes se perdían. El inventario es memoria de traducción: el mismo texto se traduce una vez, y una corrida posterior en la misma carpeta de salida reutiliza lo ya traducido (`translation.reuseInventory`).

# Traducción en lotes globales

Bugs corregidos del traductor: los ítems de `<string-array>` que eran referencias `@string/x` se enviaban al LLM, que las "traducía" y aapt2 no compilaba (birthdayadapter, fossify.clock); cada `<plurals>`/`<string-array>` generaba una llamada al LLM por contenedor (suntimeswidget: cientos de llamadas y timeout). Ahora `LLMTranslator.translate` toma las entradas del inventario, traduce los textos únicos en lotes de 25 sin distinguir contenedores, copia los ítems no traducibles tal cual y omite los contenedores sin nada que traducir. Mide y registra cada tanda (`LLM: N resource texts -> es in X s (R requests, M reused)`).

# Modelos de traducción especializados

Los modelos entrenados solo para traducir (TranslateGemma de Google, Hy-MT2 de Tencent) no siguen instrucciones de formato: ignoran el JSON por lotes y traducen todo lo que reciben, incluidos los literales de datos. Para ellos `LLMTranslator` tiene dos estilos de un texto por petición, elegidos con `translation.style` (o por el nombre del modelo con `auto`): `translategemma`, con el prompt fijo con el que Google entrenó esos modelos, y `template`, con el prompt de `translation.promptTemplate` (`{source}`, `{target}`, `{text}`; por defecto el prompt de traducción de Hy-MT2). Van cuatro peticiones en paralelo y cada respuesta pasa por la comprobación de placeholders.

Como estos modelos traducen también lo que hay dentro de los placeholders (`{name}` a `{nombre}`) o los convierten (`&amp;` a "y"), los dos estilos enmascaran por defecto (`translation.maskPlaceholders`): cada `%1$s`, `{name}`, etiqueta o entidad se sustituye por un token `<phN>` antes de la petición y se restaura después, y el escape literal `\n` viaja como salto de línea real. Con Hy-MT2-1.8B sobre 42 textos de prueba el modelo conservó 35 placeholders sin enmascarar, 38 pidiéndoselo en el prompt y 41 enmascarando. Es la misma idea que el ITDroid original aplicaba con IBM Watson.

Nota práctica: Ollama sirve un GGUF del Hub de Hugging Face directamente (`translation.model=hf.co/<repo>:<cuantización>`), pero la plantilla de chat que extrae del archivo puede salir corrupta (con Hy-MT2-1.8B el modelo respondía "onse" a todo). Conviene revisar `ollama show <modelo> --template` y, si hace falta, crear un modelo derivado con un Modelfile que lleve la plantilla oficial del repositorio (`eval/Modelfile.hy-mt2`).

# La app fuera del dispositivo mientras se traduce

Cada exploración termina con `am force-stop` y `pm uninstall`: la app solo está instalada mientras se explora. La traducción, el parcheo y la reconstrucción de cada idioma ocurren con el dispositivo limpio. El servidor LLM se mantiene activo durante toda la corrida.

# Grabar y reproducir

El problema principal de la evaluación: el explorador no es determinista (pantallas transitorias al arrancar, listas que cambian), así que dos exploraciones independientes del mismo app tomaban caminos distintos y los grafos no emparejaban (4 de las 15 apps). Ahora la exploración del idioma origen graba cada acción (tap, atrás, reinicio con su camino) en `exploration_script.json`, y cada versión traducida, una vez reconstruida e instalada, la reproduce (`AdbExplorer.replay`) guardando cada pantalla con el id del estado origen. `LayoutGraphComparision` empareja por ese id (misma actividad, número de nodos similar), y las divergencias se reportan en `replay_report.json` y en `report.json` sin abortar. `LayoutGraph` lee los estados por id, así que un estado no alcanzado deja un hueco en vez de descolocar los demás.

Las versiones traducidas se reconstruyen, instalan y evalúan de verdad; el replay solo fija el camino, la comparación se hace sobre lo que muestra cada APK traducido.

# Ventana de asentamiento y firma de estado

Tras cada arranque se espera `exploration.settleSeconds` y hasta que la estructura no cambie durante 1,5 s (antes bastaban dos volcados iguales a 0,6 s). La firma de estado incluye ahora `content-desc` (dígitos plegados) y si el nodo muestra texto, ignorando los nodos del teclado, para que las apps Compose sin `resource-id` distingan sus pantallas; la estructura solo `resource-id`+clase se conserva para comparar entre idiomas. Los campos de texto ya no se tocan como clickables (solo abrían el teclado y producían estados falsos).

# Formularios (opcional)

`exploration.fillForms=true` pide al LLM un valor por campo de texto de cada pantalla nueva, lo teclea (`adb shell input text`, cerrando el teclado) y lo graba; el replay teclea los mismos valores. Desactivado por defecto porque añade llamadas al LLM en mitad de la exploración.

# Otros

- `report.json` se reescribe tras el preflight, tras el inventario, tras la exploración origen y tras cada idioma; un timeout externo ya no pierde todo.
- `report.json` incluye `preflight`, `inventory`, y por idioma `pairedStates`, `pairing`, `patchedHardcoded`, `replay` y `error`.
- Nuevas claves de config: `preflight.launchTest`, `translation.reuseInventory`, `translation.style`, `translation.promptTemplate`, `translation.maskPlaceholders`, `exploration.maxStates`, `exploration.maxClickables`, `exploration.settleSeconds`, `exploration.fillForms`.
- Instalación con `-g` (permisos concedidos) además de la concesión tras cada `pm clear`.
- `AdbExplorer.dumpHierarchy` borra el volcado remoto antes de cada captura y comprueba la respuesta de uiautomator, con un tope mayor que su espera de "idle": un volcado fallido ya no se confunde con la pantalla anterior (en el lote 4 un reloj con segundos, cuya interfaz nunca está quieta, heredaba las pantallas de la app anterior). Las apps con interfaz en movimiento continuo se reportan ahora como no capturables.

# 11. Carpeta de resultados e informe web

ITDroid está pensado para que un desarrollador descargue el repositorio y evalúe sus propias apps; el informe web es la cara visible de esa evaluación. Antes, `webreport/` era una página de 2019 que leía un `report.json` copiado a mano y solo mostraba conteos.

Ahora cada evaluación va a `results/<paquete>/` (clave `resultsDir`, por defecto `results`; `output=` sigue valiendo como carpeta explícita, que es lo que usa el harness), y `java -jar ITDroid.jar --report [carpeta]` arranca `ReportServer` (el `HttpServer` del JDK en 127.0.0.1, sin dependencias) que sirve `webreport/`, la carpeta de resultados y un índice `/api/results` con una entrada por subcarpeta que tenga `report.json`. La página (`webreport/`, JavaScript sin frameworks ni red, Bootstrap local) lista las apps evaluadas y muestra la elegida en pestañas: resumen (APK, versión, SDK, dispositivo, modelo, duración, totales), idiomas e IPFs (por pantalla, con la captura origen y la traducida lado a lado y los nodos afectados recuadrados a partir de los `bounds` del volcado), hardcodeados (desde `strings_inventory.json`, con su origen, si se tradujeron y cómo), rendimiento, entorno y artefactos. Sirve igual para los lotes del harness (`--report eval/results/<lote>`), que además aportan la telemetría de `meta.json`.

Para alimentarlo, `report.json` ganó tres secciones: `itdroid` (versión, inicio, fin, estado), `environment` (host, dispositivo con modelo y versión de Android, motor de traducción con modelo y estilo, configuración de la corrida) y `performance` (segundos por fase: preflight, decode, inventory, build/explore del origen y translate/build/replay/compare por idioma; totales del LLM: peticiones, latencia, textos enviados, reutilizados y dejados en origen; CPU y heap de ITDroid; carga de CPU y memoria del host muestreadas cada 2 s). Lo recoge `RunMetrics`; el servidor LLM y el emulador son procesos aparte, así que su consumo aparece en las cifras del host, no en las del proceso.

# 12. Correcciones tras la auditoría de noviembre de 2026

Una auditoría comparó este refactor con el repositorio original de 2020 y dejó una lista de correcciones. Esta sección registra las que cambian el comportamiento, cada una con lo que hacía antes y lo que hace ahora.

# Red de seguridad

Problema: no había ninguna prueba, y comprobar un cambio en el comparador exigía volver a explorar las apps en el dispositivo (unas 20 horas por lote de 40 apps).

- Pruebas unitarias con JUnit 5 en `test/` (ver sección 1): 75 pruebas sobre el comparador, la detección y el parcheo de hardcoded, el inventario y el traductor (contra un servidor local que imita al LLM). Fijan con datos reales los conteos de IPFs de cuatro apps evaluadas, y con una app escrita a mano lo que el detector acierta y lo que no. Los defectos conocidos quedan como pruebas `knownGap_...`, que afirman el comportamiento actual hasta que se corrija.
- Re-análisis sin dispositivo: `java -jar ITDroid.jar --reanalyze <carpeta> [--out <carpeta>]` (`helper/Reanalysis`) recalcula la comparación a partir de los `result.json` ya guardados y deja, por evaluación e idioma, los IPFs que decía el informe y los que calcula el código actual. No usa dispositivo ni LLM y nunca escribe en la carpeta que lee (se rechaza una carpeta de salida que esté dentro de la de entrada). Sobre un lote de 40 apps en tres idiomas reproduce los 114 conteos del informe guardado en unos 7 minutos.
- `graph.txt` y `graph.json` pasan a ser opcionales. Antes `LayoutGraph` los escribía siempre junto a cada `result.json`: listan todas las relaciones entre todos los pares de nodos, ocupaban 235 MB en un lote de 40 apps, nada los leía, y hacían que cargar un grafo modificara la carpeta de resultados. Ahora solo se escriben con `debug.graphDumps=true`; su contenido no cambia.

# Detección de strings hardcoded

Problema: el detector solo miraba la carpeta del `applicationId`, no sabía para qué se usaba cada literal, ignoraba el XML, daba tres totales distintos y solo corría con un dispositivo conectado.

- El código de la app se localiza desde el manifiesto y `BuildConfig` (`helper/AppCode`), no desde el `applicationId`. Antes, una app cuyo código estaba en otro paquete reportaba 0 hardcoded y no se le parcheaba nada. Sobre las APK de referencia: micromath pasa de 0 a 653 literales distintos (código bajo `com.mkulesh.micromath`), birthdayadapter de 0 a 61 (bajo `fr.heinisch.birthdayadapter`) y fulguris de 0 a 1541 (bajo `fulguris`, 5280 `const-string` que no se leían). En las apps que ya se detectaban el conjunto de archivos leídos no cambia: con el conteo antiguo siguen dando 513 (shiftcal) y 442 (a_personal_stuff). Detector, inventario y parcheador comparten la misma lista de archivos.
- Un solo escaneo del smali (`helper/SmaliScan`). Antes se leía tres veces, con tres copias del código que recorre carpetas y dos de la función que decodifica escapes; ahora hay una pasada que da cada aparición con dex, clase, método, línea y registro, y una sola `SmaliText`. Desaparece `collectUiStrings`, que no tenía llamadores.
- Cada aparición se clasifica por quién la consume (interfaz, log, excepción, datos, compilador, o desconocido con su motivo), siguiendo el registro dentro del método y los `StringBuilder` hasta su `toString()`. La tabla de APIs es un archivo editable, `hardcoded-consumers.txt`. Antes todo literal con aspecto de texto se enviaba a traducir y se parcheaba, incluidos mensajes de log y de excepción y claves de preferencias. Ahora un literal cuyas apariciones van todas a logs, excepciones, datos o al compilador se lista pero no se traduce. Literales traducibles antes y después: shiftcal 38 y 15; a_personal_stuff 48 y 8; birthdayadapter 35 y 4; micromath 239 y 156; fulguris 1116 y 284. Lo que queda como traducible es lo que se muestra más lo que el escaneo no sabe decidir.
- Un literal que el código muestra se lista y se traduce aunque tenga forma de identificador o de constante (`cancel`, `CANCEL`); antes se descartaba por su forma.
- Los literales con un salto de línea dentro se descartaban sin aviso (la expresión que exigía una letra no cruzaba líneas). Ahora se detectan como los demás.
- Hardcoded en XML (`helper/XmlLiteralFinder`, port de `repairLayoutHCS` de la rama `repairments`): texto literal en `android:text`, `hint`, `contentDescription`, `title`, `summary`, `label` y similares de layouts, menús, preferencias, grafos de navegación y manifiesto, leyendo el XML como árbol y dejando fuera los layouts de AndroidX y Material. Antes no se buscaban. Se listan como `hardcoded-xml`; su traducción llega al extraerlos a recursos.
- Una sola cifra. Antes `hcs.txt` contaba un literal una vez por archivo, el inventario una vez por app y un tercer número contaba los traducibles (shiftcal: 513, 248 y 31). Ahora `report.json`, el inventario, `hcs.txt` y el informe web dan el mismo número, literales distintos de código más XML, con un único desglose en `report.inventory`: en código, en XML, apariciones, por uso y traducibles. La cifra no es comparable con la de versiones anteriores.
- `hcs.txt` vuelve a agruparse por clase y método, como en la herramienta original; el refactor lo había dejado solo por clase. Cada literal lleva su uso entre corchetes y el XML va al final.
- El análisis estático ya no depende del dispositivo. Antes los hardcoded se buscaban después de comprobar dispositivo, servidor LLM y arranque de la app, así que una corrida rechazada no dejaba nada. Ahora se hace antes; si el dispositivo o el LLM fallan, la corrida lo dice, termina el análisis estático y aborta. Nuevo modo `--static`, que solo decodifica y analiza.
- Una app sin `strings.xml` ya no se rechaza ("Your application do not have a strings.xml file."): se detectan y traducen sus hardcoded, y no hay recursos que traducir.
- Strings no internacionalizados por idioma: `XMLComparator` calculaba qué strings del idioma por defecto le faltaban a cada idioma y lo descartaba. Ahora `report.json` lleva `nonInternationalized` (por idioma: si la app lo traía, si tiene archivo, cuántos faltan y cuáles), y cada idioma de `langsReport` dice `shippedByApp` y su carpeta de resultados (`trnsResults` o `noTrnsResults`).
- Al contar los strings que le faltan a un idioma ya no entran los del idioma por defecto que no son texto (un número, una referencia, un identificador como `androidx_startup`, que una librería añade a todas las apps y ningún idioma traduce). Antes contaban como faltantes, y con `alpha=0` bastaba uno para tratar como no traducida una app que sí traía el idioma.
- Pruebas: la suite pasa de 75 a 109. Las pruebas `knownGap_...` de los puntos corregidos se invirtieron; quedan las de la traducción de literales de uso desconocido y de XML, y el parcheo por aparición.

# Traducción de strings hardcoded

Problema: se traducía y se parcheaba todo literal con aspecto de texto, en todas partes donde aparecía; el traductor quitaba los espacios de borde; el XML no se traducía; el APK con nombre por defecto no llevaba ninguna traducción; y un parche que rompía la app solo se detectaba si impedía el arranque.

- Política de qué se traduce (`hardcoded.translate`, `helper/HardcodedPolicy`). Por defecto `ui`: solo las apariciones que el código entrega a la interfaz. Antes, en shiftcal se traducían `"Bearer "` y `"Authorization"` (cabeceras HTTP guardadas en un mapa), nombres de fuentes de un PDF y propiedades de calendario; ahora, de sus 256 literales se traducen los que van a un diálogo. `ui+unknown` añade las apariciones de uso desconocido y `all` reproduce el comportamiento anterior. Consecuencia que conviene conocer: el texto que llega a la pantalla a través de objetos de datos (por ejemplo los nombres de una lista construida en el código) no se traduce con `ui`; hay que pedir `ui+unknown`.
- Una función composable que la tabla no conoce cuenta como interfaz solo para texto que no sea un identificador suelto. Sin esa distinción se habrían traducido nombres de iconos (`search`, `arrow_back`) que una app pasa a su propio composable de iconos.
- Parcheo por aparición (`helper/SmaliPatcher`). Antes `patch` reemplazaba un literal en todos los sitios donde estuviera; ahora recibe las apariciones elegidas (archivo y línea del escaneo), comprueba que la línea sigue teniendo ese literal y solo guarda y restaura los archivos que cambia. Un mismo texto que es etiqueta en un método y operando de `equals` en otro ya solo cambia en el primero.
- Espacios de borde. El traductor devolvía el texto recortado, así que `"Items: " + n + " of 3"` quedaba pegado al traducir (los 2625 literales con borde de un lote de 40 apps lo perdían). Ahora la traducción lleva el espacio o salto inicial y final del original, en todos los estilos de petición.
- Comillas envolventes. apktool escribe entre comillas dobles los strings que llevan apóstrofos, y un recurso con espacios de borde también va entre comillas; es sintaxis de Android, no texto. Se enviaban al modelo con las comillas, volvían como comillas (o como «» en ruso), se escapaban y la app traducida las mostraba (18 de los 484 strings de shiftcal). Ahora el modelo recibe lo de dentro y el resultado se vuelve a envolver sin escapar.
- Literales del XML a recursos (`helper/XmlLiteralExtractor`, port de `repairLayoutHCS` de la rama `repairments`). Cada literal de layout, menú, preferencias, navegación o manifiesto pasa a `@string/itdroid_hc_N` en `strings.xml`, y se traduce por la vía normal de recursos. A diferencia del original, los nombres no chocan con los de la app, un mismo texto es un solo recurso, se escapan comillas y bordes, y funciona en una app sin `strings.xml`. En un idioma que la app ya traía solo se añaden esos strings nuevos; lo que al desarrollador le faltaba traducir se deja como estaba.
- Literales del código a recursos (`helper/CodeLiteralExtractor`, en lugar del `replaceHCS` de la rama `repairments`). Las apariciones que la política traduce pasan a leerse de un recurso `itdroid_code_<hash>`: el literal se queda en el código y se entrega a una clase que ITDroid añade a la app (`itdroid.hc.Strings`), que devuelve el recurso de ese texto en el idioma en que corre la app, o el texto mismo si no lo hay. El original solo cubría métodos no estáticos de clases que fueran un `Context`, necesitaba un registro para el id y contaba los ids desde un número fijo; esta versión sirve en cualquier método, no reserva ids (busca el recurso por nombre), cubre todos los dex y deja sin mover lo que está en un dex lleno. Si la app no construye o no arranca con esa clase, los literales vuelven al código y se parchean por idioma, como antes. Se desactiva con `hardcoded.extractCode=false`.
- El nombre de la app no se traduce: ni el recurso (`app_name` o el que use el `android:label` de la aplicación), ni un literal igual a él, ni cuenta como string que le falte a un idioma. Antes un modelo podía convertir el nombre de la app en una palabra común, y a casi todas las apps "les faltaba" `app_name` en cada idioma.
- APK internacionalizado. El APK de nombre por defecto era la construcción origen, sin ninguna traducción. Ahora es una sola construcción con los recursos de todos los idiomas procesados, incluidos los hardcoded movidos a recursos: cambia de idioma con el del dispositivo.
- Detección de parches que rompen la app. Antes el respaldo solo saltaba si el APK parcheado no llegaba a primer plano. Ahora el explorador guarda los crashes de cada replay y los del original durante la exploración; si el APK parcheado crashea con una excepción que el original no dio, el idioma se reconstruye sin parches y se vuelve a evaluar, y `report.json` deja la causa (`hardcodedFallback`), cuántos literales se revirtieron y los crashes.
- Registro revisable. `hardcoded_<idioma>.csv` pasa de tres columnas (original, traducido, cambiado) a una fila por aparición: qué se hizo (parcheado, extraído, revertido, no traducido), por qué, archivo, línea, clase, método, uso y la llamada que recibe el texto.
- Modelo de traducción PLaMo Translate (`translation.style=plamo`, automático por nombre de modelo): un texto por petición, con los códigos de idioma en el mensaje de sistema y los placeholders enmascarados (el modelo rompe `%1$s` si los ve). Requiere Ollama 0.40 o posterior.
- El paquete que se pasa a ITDroid se comprueba contra el del APK. Antes, con el paquete de otra app nada fallaba de inmediato: el lanzamiento no encontraba actividad y la exploración acababa tocando el launcher. Ahora la corrida se rechaza diciendo cuál es el paquete real.
- Comprobado en el emulador (API 33) con PLaMo: shiftcal evaluado en español y ruso (los 3 literales de XML extraídos y traducidos, sin crashes); la app de prueba con sus 14 literales de código movidos a recursos (17 apariciones, en un inicializador estático) se reconstruye, arranca y se reproduce 14 de 14 en los dos idiomas; su APK internacionalizado, con una sola instalación, muestra `France / Lusail Stadium`, `Francia / Estadio Lusail` y `Франция / Стадион Лусайл` según el idioma; y un crash provocado durante el replay de una build parcheada hace que ese idioma se reconstruya sin parches y quede registrado. La suite queda en 140 pruebas.

# Traductor

Problema: en el estilo por lotes nadie comprobaba que los placeholders sobrevivieran; el enmascarado tomaba un porcentaje por un formato; se guardaba como traducción cualquier cosa que devolviera el modelo; y con un modelo de traducción, que no admite instrucciones, nada impedía que tradujera o transliterara un nombre propio.

- Términos protegidos (pedido del usuario: los nombres propios no se traducen). Una lista editable, `protected-terms.txt` en la carpeta de settings, con los nombres que deben salir tal como entran. Cada término viaja como un token, igual que los placeholders, y se restaura después; una traducción que lo pierde se descarta. También se protegen así, sin listarlos, las direcciones de correo y las URL dentro de un texto, y el nombre de la app cuando es reconocible como nombre. Con PLaMo, `Welcome back, Juan Pérez` daba en ruso `Хуан Перес`; con el nombre en la lista sale `Juan Pérez`. Límite: un nombre que no está en la lista se traduce como cualquier otra palabra.
- Claves y credenciales (mismo pedido). Las llamadas que reciben una contraseña, un token o un usuario (`setPassword`, `setToken`, `login`, `okhttp3.Credentials`, `androidx.credentials`...) se clasifican como datos, y un texto con forma de clave o token (una tira larga de letras y dígitos sin espacios) no se traduce, sea recurso o literal.
- Validación de placeholders también en el estilo por lotes. Antes una traducción sin el `%1$s` de su origen se aceptaba y la app fallaba al formatearla; ahora se rechaza, se reintenta y, si vuelve igual, queda el texto origen, como ya hacían los estilos de un texto por petición.
- Enmascarado de `%`. La expresión tomaba `% o` de `50% of users` por un formato (el espacio es un flag válido de printf) y dejaba letras sueltas en la traducción. Ahora un `%` es formato solo si va doblado, lleva índice o le sigue una conversión y no viene tras un dígito.
- Controles sobre la respuesta. Se descarta la respuesta vacía, la desproporcionadamente larga, la que es un comentario del modelo sobre la tarea y la que no usa el alfabeto del idioma destino. Antes `English` podía quedar traducido como `Nota: debe solo mostrar el resultado traducido...`. Los rechazos se cuentan en `report.json` (`performance.llm.answersRejected`).
- PLaMo Translate: una línea de contexto para los textos de hasta tres palabras, que solos son ambiguos (`Open` daba `Abierto` y ahora `Abrir`; `Decline`, `Declive` y ahora `Declinar`; `Play`, `Jugar` y ahora `Reproducir`). No acierta siempre: `Matches`, que en la app de prueba son partidos, pasa de `Partidas` a `Coincidencias`. Se desactiva con `translation.context=none`. Y un idioma que el modelo no soporta ya no se evalúa con lo que devuelva: pedía portugués y respondía en francés.
- Limpieza: los textos pendientes se deduplican con un conjunto en vez de buscar en una lista (era cuadrático con miles de textos).

# Construcción con apktool

Problema: una app que apktool no reconstruye tal como la decodificó se rechazaba entera, aunque el obstáculo fuera un detalle sin efecto en la evaluación.

- Atributos `android:` que apktool no conoce. Una app compilada para un Android más nuevo que el framework de apktool usa atributos que aapt2 rechaza (`android:pageSizeCompat` en una app con targetSdk 36). ITDroid quita ese atributo de la app decodificada y vuelve a construir.
- Atributos de la app cuya declaración se pierde al decodificar (`state_with_icon`, de Material 3). ITDroid lo vuelve a declarar en `res/values/attrs.xml` y reconstruye. Como el código de la app busca el id que tenía el atributo, el estado que marca no llega a aplicarse: la variante del interruptor con icono no se dibuja.
- Los ajustes quedan en `report.json` (`buildAdjustments`). Con los dos, org.tasks, que antes se rechazaba, se reconstruye, arranca y se evalúa.

# Comparador de layouts

Problema: los elementos de dos pantallas se emparejaban por su posición en el volcado, así que un elemento de más o de menos desalineaba todos los siguientes; una pantalla con distinto número de nodos que el origen no se comparaba; en un idioma de derecha a izquierda el espejado, que es lo correcto, contaba como fallo; dos elementos que se pisaban compartiendo un borde no se detectaban; y el resultado era un solo número sin decir qué había cambiado.

- Regla de los textos, como en 2020. Un cambio de alineación se ignora cuando cualquiera de los dos elementos del par es un `TextView`; el refactor miraba solo el primero. Medido aparte, antes de los demás cambios: el lote pasa de 1623 / 2053 / 3396 (es / ru / ar) a 1569 / 1998 / 3231, y birthdayadapter en árabe de 157 a 91.
- Emparejar elementos, no posiciones (`model/NodePairing`). Los dos árboles se recorren desde arriba y los hijos de cada par se alinean en orden, como un diff: se emparejan los que son el mismo tipo de elemento (misma clase y mismo resource-id; después lo que los distingue entre hermanos: si son clicables, si llevan texto, cuánto cuelga de ellos; y la posición solo para desempatar). Un elemento insertado o quitado cuesta ese elemento y los demás se comparan con su pareja real. En las 373 pantallas del lote con el mismo número de nodos que el origen el emparejamiento coincide elemento a elemento con el posicional. El código anterior calculaba además miles de distancias de Levenshtein entre nodos para acabar devolviendo siempre la identidad: re-analizar el lote tardaba 442 s y ahora unos 2 s.
- Una etiqueta de Compose no es un id. El volcado pone en `resource-id` las etiquetas de prueba de Compose, y hay apps que usan como etiqueta el texto visible (`Username` en inglés, `Nombre de usuario` en español). Solo cuenta como id el que tiene forma de recurso (`paquete:id/nombre`); si no, en la pantalla de inicio de sesión de shelfdroid 14 de los 39 elementos salían como perdidos y otros 14 como añadidos, siendo los mismos.
- Los elementos sin pareja son hallazgos. Un elemento del origen que la traducción no tiene es un componente perdido, y al revés uno añadido; se reporta una vez, en lo más alto de lo que falta o sobra, con cuántos elementos lo componen. Ya no se exige un número de nodos parecido para comparar una pantalla reproducida: se compara si está en la misma actividad y se logra emparejar al menos la mitad de los elementos de la pantalla mayor (clave `comparison.minSimilarity`, 0,5 por defecto). Por debajo, la app mostró otra cosa en ese paso, y la pantalla queda en `statesNotCompared` con el motivo. En el lote, de 44 pantallas con distinto número de nodos, 26 se parecen al origen en un 85 % o más, 8 en un 21 % o menos, y las 10 intermedias son todas de micromath, un editor cuyo documento cambia; solo a esa app le afecta el umbral. micromath pasa de 0 pantallas comparadas a 1, 2 y 3 (ar, es, ru), y candle en árabe de 4 a 5.
- Comparación consciente de la dirección de escritura (port de la rama `repairments`: `languageIsRTL`, los tipos que se espera que cambien, `rtlMissingChanges`, `nodesAreSiblings`). Entre un idioma de izquierda a derecha y uno de derecha a izquierda, que izquierda y derecha se intercambien, y con ellas el lado de la alineación, ya no se reporta. Se reporta lo contrario: dos elementos del mismo contenedor que no se intercambiaron, con el tipo `missingMirroring` y la relación que debería estar en el campo `missing`. shiftcal, que espeja bien, pasa en árabe de 297 a 0 (169 pares de hermanos revisados, todos intercambiados); birthdayadapter, que no espeja, queda en 44, todos falta de espejado (61 de 61 pares sin intercambiar).
- Solapamiento real. `INTERSECTS` solo se marcaba cuando no había ninguna otra relación entre dos elementos; dos botones de la misma fila que se pisan comparten borde superior e inferior y pasaban por alineados. Ahora dos elementos se solapan cuando comparten área y ninguno contiene al otro, estén alineados o no.
- Cada hallazgo dice qué es. `types` clasifica el cambio (posición perdida o ganada, alineación perdida o ganada, solapamiento nuevo o desaparecido, falta de espejado, componente perdido o añadido) y `geometry` dice qué le pasó al elemento mismo (igual, movido, redimensionado, espejado). `ipfSummary` da por idioma el desglose: elementos comparados, los que cambiaron de sitio o de tamaño (`nodesChanged`), los que siguen igual y solo tienen un vecino que cambió (`nodesAffected`), componentes perdidos y añadidos, y cuántos hallazgos llevan cada tipo. `amIPFs` se conserva y es la suma de esas cuatro partes.
- Orden estable. Los conjuntos de relaciones eran `HashSet` de un enum, cuyo orden cambia de una corrida a otra; ahora son `EnumSet` y los hallazgos se ordenan por severidad, pantalla y elemento. Dos re-análisis del mismo lote en procesos distintos escriben archivos idénticos salvo la fecha.
- Desaparecen `IPF` e `IPFComparator`, que solo usaba la comparación anterior.
- Informe web: cada elemento se dibuja en la captura traducida donde está en esa pantalla (antes, en la misma posición del volcado que en el origen), y la tabla dice el tipo de cada hallazgo, los componentes perdidos o añadidos y las pantallas que no se compararon con su motivo. El resumen por tipo queda para la fase del informe.
- Pruebas: la suite pasa de 154 a 197. Las pruebas `knownGap_...` del comparador se invirtieron y se añaden las del emparejamiento (`NodePairingTest`), las de idiomas de derecha a izquierda (`RtlComparisonTest`), el desglose por tipo y el de las cuatro evaluaciones guardadas.

Antes y después sobre el lote de 40 apps en es, ru y ar (38 apps con datos por idioma, 114 comparaciones), recalculado con `--reanalyze` sobre las mismas exploraciones:

| | es | ru | ar |
|---|---|---|---|
| IPFs del informe guardado | 1623 | 2053 | 3396 |
| IPFs con el comparador nuevo | 1668 | 1959 | 870 |
| Pantallas comparadas, antes y ahora | 132 y 135 | 129 y 133 | 134 y 137 |
| Elementos comparados | 5816 | 5795 | 5780 |
| Cambiaron de sitio o de tamaño | 982 | 1156 | 337 |
| Siguen igual, cambió un vecino | 662 | 742 | 511 |
| Componentes perdidos | 12 | 36 | 12 |
| Componentes añadidos | 12 | 25 | 10 |
| Hallazgos con falta de espejado | no aplica | no aplica | 555 |

En árabe se revisaron 4463 pares de elementos hermanos situados uno al lado del otro, y 1067 no se intercambiaron.

Por qué estas cifras ya no se comparan con las del paper de 2020. El comparador de entonces emparejaba los nodos por su posición en el volcado, no comparaba una pantalla que difiriera del origen en más de un nodo y contaba como fallo cada relación de izquierda o derecha que cambiaba, también en árabe. El comparador nuevo cuenta otra cosa en tres puntos: compara cada elemento con el que le corresponde, incluye como hallazgos los elementos que faltan o sobran, y en un idioma de derecha a izquierda reporta la falta de espejado en vez del espejado. En español y ruso el total queda cerca del anterior (1668 frente a 1623, 1959 frente a 2053): casi todas las pantallas tenían el mismo número de nodos, y en ellas el emparejamiento no cambia; aun así no es el mismo conjunto de hallazgos, porque entran los solapamientos con borde compartido y salen las alineaciones de los textos. En árabe baja de 3396 a 870, sobre todo porque lo que se contaba era el espejado mismo (shiftcal de 297 a 0, stochapop de 376 a 30). Para comparar con resultados anteriores `amIPFs` sirve solo como orden de magnitud; lo que describe una app es el desglose.

Límites conocidos. La alineación de hermanos conserva el orden: dos hermanos que intercambian su orden en el volcado salen como uno perdido y uno añadido (no se vio en el lote; el orden del volcado es el del árbol de vistas, que una traducción no cambia). Las filas idénticas de una lista solo se distinguen por orden y posición: si desaparece una del medio, la pérdida se atribuye a la última. Y `missingMirroring` señala cualquier par de hermanos que no se intercambió, también los que por diseño no se espejan (controles de reproducción, un teclado numérico): va como tipo aparte para poder revisarlo o dejarlo fuera.

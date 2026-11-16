# ITDroid Refactorización y modernización

Cambios realizados al modernizar ITDroid y reemplazar su motor de exploración, cada uno con su justificación.

Resumen: ITDroid quedó actualizado a Java 21, se reemplazó el traductor IBM Watson (deprecado) por un LLM local, se actualizaron los binarios externos, se corrigieron bugs, y se reemplazó el motor de exploración cerrado (RIP, 2017), que se colgaba en Android moderno, por uno propio basado en `adb` que no necesita root. El flujo completo (decode, traducir, reconstruir, explorar, detectar IPFs, reporte) se validó de punta a punta.

El documento sigue el orden en que se hizo el trabajo. Las secciones 1 a 9 son la modernización inicial; la 10, el flujo rediseñado tras evaluar apps reales; la 11, la carpeta de resultados y el informe web; y la 12, las correcciones de la auditoría de noviembre de 2026, que comparó este refactor con la herramienta de 2020. Una sección posterior corrige a veces lo que dice una anterior: lo vigente es lo último, y la sección 12 termina con la validación final y lo que queda pendiente.

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
- Calidad de traducción: un modelo pequeño comete deslices ocasionales (traduce nombres propios). Desde la sección 12 los nombres que se listan en `protected-terms.txt` no llegan al modelo, y una respuesta que no es una traducción se descarta.
- `exploration.maxStates` limita la cobertura por app; se puede subir en el archivo de config.
- Apps cuyas pantallas cambian entre arranques (diálogos de primer uso, avisos) pueden divergir en el replay; las divergencias quedan en `replay_report.json`.

La lista vigente de límites y de trabajo pendiente está al final de la sección 12.

# 9. Cómo ejecutar

1. Instalar un LLM local (Ollama o llama.cpp).
2. Tener un emulador/dispositivo Android 13+ (API 33+) conectado por `adb` (uno solo).
3. `mvn clean package`.
4. Copiar `itdroid.config.example.properties` a `itdroid.config.properties`, poner el APK, su paquete, el emulador y el modelo, y ejecutar `java -jar target/ITDroid-1.0.0.jar itdroid.config.properties`. Los resultados quedan en `results/<paquete>/` y se ven con `java -jar target/ITDroid-1.0.0.jar --report`.

La forma original sigue valiendo: `java -jar target/ITDroid-1.0.0.jar <APK> <paquete> extra/ . <alpha> <salida> <emulador> ["comando-de-arranque-del-LLM"]`, con el LLM configurado en `.env` (copia de `.env.example`). El 8º argumento es opcional: si se pasa, ITDroid arranca el servidor LLM y lo apaga al final; si se omite, el servidor debe estar ya corriendo.

Ver README.md para el detalle de cada clave y de los otros modos (`--static`, `--report`, `--reanalyze`).

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

Nota práctica: Ollama sirve un GGUF del Hub de Hugging Face directamente (`translation.model=hf.co/<repo>:<cuantización>`), pero la plantilla de chat que extrae del archivo puede salir corrupta (con Hy-MT2-1.8B el modelo respondía "onse" a todo). Conviene revisar `ollama show <modelo> --template` y, si hace falta, crear con `ollama create` un modelo derivado: un Modelfile con `FROM hf.co/tencent/Hy-MT2-1.8B-GGUF:Q8_0`, un `TEMPLATE` con la plantilla oficial del repositorio del modelo (su `chat_template.jinja`) y los `PARAMETER stop` de sus marcas de fin de turno.

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

Ahora cada evaluación va a `results/<paquete>/` (clave `resultsDir`, por defecto `results`; `output=` sigue valiendo como carpeta explícita, que es lo que usa el harness con el que se evaluaron los lotes de apps: unos scripts de PowerShell que lanzan ITDroid app por app y miden la máquina, y que no forman parte del repositorio), y `java -jar ITDroid.jar --report [carpeta]` arranca `ReportServer` (el `HttpServer` del JDK en 127.0.0.1, sin dependencias) que sirve `webreport/`, la carpeta de resultados y un índice `/api/results` con una entrada por subcarpeta que tenga `report.json`. La página (`webreport/`, JavaScript sin frameworks ni red, Bootstrap local) lista las apps evaluadas y muestra la elegida en pestañas: resumen (APK, versión, SDK, dispositivo, modelo, duración, totales), idiomas e IPFs (por pantalla, con la captura origen y la traducida lado a lado y los nodos afectados recuadrados a partir de los `bounds` del volcado), hardcodeados (desde `strings_inventory.json`, con su origen, si se tradujeron y cómo), rendimiento, entorno y artefactos. Sirve igual para la carpeta de un lote (`--report <carpeta del lote>`); si cada evaluación trae además un `meta.json` con la telemetría que midió el harness, la página la muestra.

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

Límites conocidos del comparador. La alineación de hermanos conserva el orden: dos hermanos que intercambian su orden en el volcado salen como uno perdido y uno añadido (no se vio en el lote; el orden del volcado es el del árbol de vistas, que una traducción no cambia). Las filas idénticas de una lista solo se distinguen por orden y posición: si desaparece una del medio, la pérdida se atribuye a la última. Y `missingMirroring` señala cualquier par de hermanos que no se intercambió, también los que por diseño no se espejan (controles de reproducción, un teclado numérico): va como tipo aparte para poder revisarlo o dejarlo fuera.

# Informe web, privacidad y corridas interrumpidas

Problema: los enlaces de la pestaña de artefactos apuntaban siempre a `noTrnsResults/` y fallaban en los idiomas que la app ya traía; la pestaña de hardcoded era una sola tabla sin búsqueda ni páginas (más de 2000 filas en algunas apps) y daba por traducido lo planeado, no lo aplicado; los strings que le faltan a cada idioma se calculaban y no se mostraban; los IPFs salían sin tipo; `report.json` llevaba el nombre de usuario del sistema y rutas absolutas de la máquina; y una corrida matada quedaba para siempre como "running".

- Enlaces de artefactos. El servidor lista los archivos de cada evaluación (`/api/files/<evaluación>`) y dice en qué carpeta está la exploración de cada idioma; la página enlaza solo lo que existe, con su tamaño. En librecontactsbackup, que trae español y árabe, los 34 enlaces responden; antes los de esos dos idiomas daban 404.
- Pestaña de hardcoded en los grupos del informe original: extraídos del código a recursos, extraídos del XML y no traducidos con su motivo, más los parcheados por idioma. Cada string lleva su recurso, clase, método, uso y cada lugar donde aparece. Cada string está en un solo grupo y los grupos suman el total de `report.json` (shiftcal: 2 + 3 + 0 + 254 = 259). Una caja de búsqueda y un filtro por uso sirven a todos los grupos, y cada grupo se pagina de 50 en 50 (suntimeswidget tiene 2256 strings). La tarjeta de traducidos y una tabla por idioma dicen lo que quedó aplicado en cada build: strings leídos de recursos, lugares parcheados y, si la build de un idioma falló y se reconstruyó sin ellos, cuántos se revirtieron y por qué.
- Strings no internacionalizados: pestaña nueva con, por idioma, si la app lo traía o lo tradujo ITDroid, cuántos strings por defecto le faltan y cuáles, con su texto y la traducción.
- IPFs por tipo: el desglose de `ipfSummary` por idioma, un botón por tipo de cambio que filtra las pantallas, la etiqueta "Missing mirroring" en cada elemento sin espejar y, para un idioma de derecha a izquierda, una tercera imagen con la posición estimada que tendría cada uno en la pantalla espejada (port de la vista "Estimated Position" del informe React: a la misma distancia del borde derecho que hoy del izquierdo). `#<evaluación>/languages/<idioma>/<pantalla>` abre directamente una pantalla.
- Privacidad y portabilidad. Antes `report.json` guardaba `environment.host.user`, `apkPath` y `outputFolder` como rutas absolutas, y cada `result.json` la ruta absoluta de sus capturas. Ahora el nombre de usuario no se guarda; `apkPath` es la ruta desde la carpeta de resultados (o el nombre del archivo si el APK está fuera) y el APK se identifica por `apkSha256`; `outputFolder` es el nombre de la carpeta; `screenShot` es `screenshots/<archivo>.png`; y cualquier otra ruta absoluta que acabe en un texto (el mensaje de un error) pierde su inicio, sustituido por `<output>`, `<itdroid>` o `~` (`helper/PortablePaths`). La cabecera de la página muestra el nombre de la carpeta servida, no su ruta. Comprobado con una corrida completa en el emulador: ningún archivo de la carpeta contiene el nombre de usuario, y la carpeta copiada a otro sitio se sigue leyendo entera, capturas incluidas.
- Corridas interrumpidas. `report.json` se escribe desde el primer segundo (antes, la primera escritura llegaba tras el análisis estático, y una corrida que moría decodificando no dejaba nada). Lleva el `pid` del proceso y la hora en que arrancó. Si la JVM se cierra con la corrida a medias (Ctrl+C), un gancho de cierre deja el estado en `interrupted`. Si el proceso muere sin poder escribir, el servidor del informe lo detecta: un informe que dice `running` cuyo proceso ya no existe se muestra como `interrupted`, con el motivo; un informe antiguo sin `pid` se da por interrumpido si lleva más de tres horas sin reescribirse. Comprobado matando una corrida: mientras vive sale `running`, y tras matarla, `interrupted`.
- Todas las pantallas. Antes solo se podían abrir las pantallas con IPFs. "Every screen" lista todas las de la exploración origen con lo que pasó con cada una: con hallazgos, comparada sin hallazgos, alcanzada pero no comparada (con el motivo) o no alcanzada por el replay. Un elemento que solo tiene una de las dos pantallas se dibuja en esa pantalla con su propio color. Cada pantalla lleva además la diferencia visual entre las dos capturas, hecha con un `canvas` (el informe React usaba ResembleJS): el origen en gris y en magenta cada píxel distinto; en un idioma de la otra dirección de escritura la captura traducida se voltea antes, para que un layout bien espejado coincida con el origen.
- Prueba de la página sin navegador: `node webreport/test/smoke.js` arranca el servidor sobre una carpeta de ejemplo (`webreport/test/sample`, 67 KB: una app inventada con español, árabe, ruso y portugués que entre todos muestran cada caso del informe, y una corrida matada), ejecuta `main.js` con un sustituto mínimo del DOM y hace 74 comprobaciones; con `--url` comprueba cualquier carpeta servida. Los hallazgos del ejemplo los calculó ITDroid con `--reanalyze` sobre pantallas escritas a mano. No entra en `mvn test` porque necesita Node. Lo único que no cubre es el `canvas` de la diferencia visual, que se comprobó con capturas del navegador en modo headless.
- Pruebas: la suite de Java pasa de 197 a 221 (`PortablePathsTest`, `ReportServerTest`, `ITDroidReportTest`).

# Copias de APK

Problema: cada evaluación dejaba entre cinco y siete APK del tamaño de la app (el que apktool escribe sin firmar, el de nombre por defecto, el del idioma origen y uno por idioma); en un lote de 40 apps eran el 89 % del disco.

- El APK sin firmar (`<paquete>.apk`) se borra en cuanto existe el firmado. Nada lo volvía a usar. Si la firma falla se conserva, que es lo que hay para mirar.
- Antes de firmar se borra el APK firmado de la construcción anterior. Todas las construcciones de una corrida se firman con el mismo nombre, y `buildAPK` daba por buena la construcción si ese archivo existía: si la firma de un idioma fallaba, el APK del idioma anterior se habría instalado y evaluado como si fuera el suyo. Ahora, sin archivo firmado, la construcción falla y lo dice.
- La construcción origen ya no se duplica. Si el APK internacionalizado no se podía construir, se copiaba la construcción origen con el nombre por defecto, y quedaba dos veces. Ahora no queda archivo con ese nombre, y `internationalizedApk` en `report.json` dice `built: false` y nombra la construcción origen.
- Clave nueva `output.keepLanguageApks` (por defecto `true`, el comportamiento de siempre). Con `false`, el APK de cada idioma destino se borra al terminar su evaluación: solo hacía falta para instalarlo y reproducir el recorrido. Quedan la construcción origen y el APK internacionalizado, que lleva los recursos de todos los idiomas; lo único que tenían de más los APK por idioma son los hardcoded parcheados en el código, que `hardcoded_<idioma>.csv` lista. La pestaña de artefactos del informe lo indica.
- Medido en el emulador con la app de prueba (58 MB): una corrida con dos idiomas dejaba 5 APK, 290 de los 292 MB de la carpeta. Con un idioma y `output.keepLanguageApks=false` quedan 2 (el internacionalizado y la construcción origen), 118 MB, y la evaluación sale igual (replay 14 de 14). El caso en que el APK internacionalizado no se construye no se ejercitó en el dispositivo.

# Jar ejecutable

Problema: `json-simple 1.1.1` declara JUnit 4.10 como dependencia normal, cuando es de pruebas, y el jar ejecutable arrastraba JUnit 4 y Hamcrest.

- Se excluyen `junit:junit` y `org.hamcrest:hamcrest-core` de esa dependencia en el `pom.xml`. El jar pasa de 1010 a 698 archivos (de 1,40 a 1,13 MB) y ya no contiene `org/junit`, `junit/` ni `org/hamcrest`. Las pruebas del proyecto usan JUnit 5 con alcance de pruebas, que nunca entró en el jar.

# Limpieza

Problema: quedaban en el código métodos y campos que nada usa, restos de la herramienta de 2020 y de pasos intermedios del refactor, y la carpeta del informe traía dos archivos de Bootstrap que la página no carga.

- Código sin uso que se quita: los `set...` de `State`, `LayoutGraph` y `Transition` que nadie llamaba (un grafo se construye al leer `result.json` y no se modifica después); `LayoutGraph.AMOUNT_STATES`, `getScriptPath` y `getLanguage`; `State.getOutboundTransitions`; `AndroidNode.FALSE`, `getContentDesc` y la referencia que cada nodo guardaba a su pantalla; el tipo de cada propiedad en `AndroidNodeProperty`; el nombre de paquete que `Helper` guardaba y nadie leía; `SmaliScan.decodedPath`; `LLMTranslator.getInventory`; `HardcodedStringFinder.isLikelyUiString`; y un constructor vacío en `AdbExplorer`. El compilador ya no da ningún aviso de código sin usar.
- Se conservan tres lectores que hoy nadie llama (`AndroidNode.isEnabled`, `State.getScreenShot`, `Transition.getOriginNode`): exponen datos que el modelo ya lee del volcado, y quitarlos sería dejar de leerlos.
- `webreport/js/bootstrap.bundle.js` (230 KB) y `webreport/css/bootstrap.css.map` (492 KB) salen del repositorio: la página solo carga `bootstrap.css`.
- No cambia ningún comportamiento: pasan las mismas 226 pruebas y las 74 comprobaciones de la página.

# Primera validación

Quince APK de apps reales se corrieron en el emulador (Android 13, API 33, x86_64) en español, ruso y árabe, con PLaMo Translate y todos los valores por defecto (`hardcoded.translate=ui`, 5 pantallas, 8 toques por pantalla), con la versión de ese momento: la que describen las secciones anteriores a esta. Tres no se pueden ejecutar en ese emulador y el preflight las rechazó en un segundo, antes de decodificar: fit_book, counted y oxu solo traen código nativo arm64. Las otras 12 se evaluaron en los tres idiomas; cuatro de ellas (ambio, salomax, locreminder y superproductivity) en una segunda pasada, porque en la primera el emulador se colgó durante su evaluación o agotaron el límite de tiempo.

La tabla de cifras de esa validación ya no está aquí. Al terminar todo lo que sigue, la validación se repitió entera con las mismas apps y una sola versión: esas son las cifras vigentes y están en "Validación final", que también dice qué cambió de una a otra. Esta sección conserva lo que la primera enseñó y no se sabía, porque de ahí salen las secciones siguientes. Las cifras que cita son las de entonces.

- Apps hechas con un framework multiplataforma. En 3 de las 12 el texto de la interfaz no está ni en `res/values` ni en el código Dalvik: vibe y superproductivity son una página web dentro de la app (Capacitor) y locreminder es Flutter. ITDroid tradujo sus recursos de Android, que en ellas son poco más que permisos y notificaciones, y la pantalla no cambió. vibe y superproductivity traen su propia traducción al español y la mostraron; en ruso y árabe se quedaron en inglés (31 de 31 y 30 de 30 textos iguales). En locreminder, entre 19 y 21 de 21. En esos casos 0 hallazgos no quiere decir que la traducción quepa, sino que no hubo traducción en pantalla; y en árabe los hallazgos de vibe y superproductivity (74 y 69) eran todos falta de espejado. El informe no lo avisaba (ver "Aviso cuando el idioma no llegó a la pantalla"). Esta lista incluía a tasks, por sus recursos de Compose Multiplatform y porque sus 35 textos salían iguales en los cuatro idiomas: era un error de ITDroid, no de la app (ver "El idioma que algunas apps quitan al arrancar").
- Apps cuyo recorrido no se repite. locreminder reproducía entre 6 y 18 de 18 acciones según el idioma y la pasada, y sus cifras cambiaban de una pasada a otra: 66 / 0 / 10 en la primera y 0 / 0 / 29 en la segunda. Sus hallazgos hay que leerlos junto a la tasa de replay. salomax, que reproducía 13 de 30 acciones en los tres idiomas con las mismas 17 divergencias, y daba 2 / 2 / 58 en una pasada y 7 / 7 / 62 en otra, no era este caso: arrancaba en inglés y cambiaba de idioma a mitad del recorrido (ver "El idioma que algunas apps quitan al arrancar").
- Apps que se quedan en la puerta. audiobook llega a 3 pantallas, las de bienvenida, porque sin una carpeta de audiolibros no tiene más; lissen no pasa de la conexión a su servidor y de sus ajustes; tasks, de la bienvenida, las condiciones de uso y el inicio de sesión. Sin datos o sin una cuenta la exploración evalúa la entrada de la app, no la app (ver "Contenido antes de explorar").
- El idioma de origen. bonavortaro tiene sus textos por defecto en esperanto y se tradujo como si fueran inglés, que es el `defaultLng` de `settings.properties` para todas las apps; lo que pasó los controles es transliteración.
- Calidad del modelo. Con un modelo de 0,8 mil millones de parámetros, en los hardcoded traducidos `Balances` (saldos) sale como `Equilibro`, `Resume` (reanudar) como `Resumen` y `Snooze 1h` como `Desconectar 1h`. Y reescribía las cifras con el formato del idioma: `6,568.65 EUR` daba `6.568,65 EUR` en español y `6 568,65 евро` en ruso, sin que los controles de la respuesta lo detectaran (ver "Cifras, idioma de origen y dispositivo").
- El emulador. En unas 11 horas de dispositivo se colgó dos veces y se cerró solo una; tras el cierre hubo que arrancarlo sin ventana y con dibujo por software (`-no-window -gpu swangle_indirect`). ITDroid no se recuperaba de eso por sí mismo: en ambio, durante la primera pasada, el idioma en curso quedó a medias y los dos siguientes con el error `could not install`, y la corrida terminó como `finished` con esos errores anotados por idioma. Y en audiobook en árabe un volcado capturó un diálogo del sistema ("System UI isn't responding") en lugar de la app: con 1 de sus 12 elementos en común con los 21 del origen, quedó en `statesNotCompared` con su motivo en vez de compararse con una pantalla que no era la suya.
- Tiempo. La corrida de una app con tres idiomas duró 31 minutos de mediana (entre 18 y 100). Lo caro eran los reinicios del replay: una app que es una página web tarda en estar lista cada vez (superproductivity, 57 minutos, y más de 75 en la primera pasada), y cada divergencia añade reintentos (salomax, 100). De ahí la sección siguiente.

# Explorador más rápido

Problema: la exploración y el replay son el 95 % del tiempo de una corrida. Se daba por hecho que el coste estaba en los tres procesos `adb` que lanza cada volcado de pantalla y en las pausas fijas tras cada toque.

- Dónde se va el tiempo, medido en el emulador. Lanzar un proceso `adb` cuesta 0,05 s; una captura de pantalla, 0,5 s; un volcado de `uiautomator`, 2,4 s, porque arranca un proceso en el dispositivo y no vuelca hasta que la interfaz lleva un segundo sin cambios. Lo caro no es el número de procesos sino el número de volcados: el explorador hacía siempre al menos dos, con 0,6 s entre ellos, para dar una pantalla por asentada, y tras cada reinicio rehacía el camino con dos por paso. En rereminder los cuatro reinicios de un recorrido eran dos tercios del tiempo.
- Un volcado basta cuando la pantalla es conocida. Al volver con atrás, al rehacer un camino tras un reinicio y al reproducir un toque que lleva a una pantalla ya capturada, el explorador sabe qué firma debe encontrar. Si el primer volcado la muestra, se acepta: esa estructura ya se capturó asentada antes, y `uiautomator` acaba de esperar un segundo de quietud. Si muestra otra cosa, sigue el procedimiento de siempre. Una pantalla que se ve por primera vez, el primer arranque de cada versión y un toque que en el origen no cambió de pantalla siguen pasando por los dos volcados.
- Un volcado ya no se repite para volver a leer lo mismo: tras un reinicio se volcaba para asentar, se descartaba y se volvía a volcar para buscar el elemento del primer paso; y al llegar por primera vez a una pantalla por un reinicio o un atrás se volcaba una vez para comprobar y otra para capturar.
- Los tres pasos de un volcado (borrar el archivo anterior, volcar, leerlo) van en un solo proceso `adb`. Ahorra 0,1 s por volcado; se conserva la garantía de que un volcado fallido no se confunde con el de la pantalla anterior, porque el archivo solo se lee si el volcado dijo haber escrito.
- Pausas fijas. Tras cada toque se esperaban 0,9 s y tras cada atrás 0,7 s. Lo que esa pausa cubría era el arranque de una ventana nueva (otra actividad, un diálogo, otra app). Tras un atrás, y tras un toque que lleva a una pantalla ya conocida, ahora se espera a que cambie la ventana con el foco, con un tope de 0,4 s; un cambio dentro de la misma ventana no necesita pausa, porque el volcado que sigue espera por sí mismo. El toque cuyo resultado no se conoce (el que prueba un elemento durante la exploración) conserva sus 0,9 s: de esa pausa depende que el explorador vea o no un aviso que dura tres segundos, y cambiarla cambiaba las pantallas encontradas (ver "Presupuesto de exploración").
- Otra app que tarda en aparecer. Si un toque abría un navegador que arrancaba en frío, la comprobación de que la app seguía en primer plano llegaba antes que el navegador y su pantalla se capturaba como si fuera de la app. La comprobación se hace ahora antes del volcado y otra vez después.
- El recorrido termina donde termina el presupuesto. Al capturar la última pantalla permitida, el explorador volvía hacia atrás nivel por nivel, con un atrás o un reinicio en cada uno, sin nada más que tocar; esas acciones quedaban grabadas y cada idioma las repetía. En rereminder eran 3 de los 4 reinicios y un tercio del tiempo de cada recorrido. Ahora el recorrido acaba en cuanto se agota el límite de pantallas o de tiempo, y el guion grabado termina en el toque que llevó a la última pantalla.
- Mismo resultado, menos tiempo. Las dos versiones del explorador en el mismo emulador y el mismo día:

| | Exploración del origen | Replay de un idioma | Acciones grabadas | Reinicios | Hallazgos es / ru / ar |
|---|---|---|---|---|---|
| rereminder, antes | 223 s | 242 s | 14 | 4 | 133 (solo español) |
| rereminder, ahora | 110 s | 100 s | 10 | 1 | 133 / 162 / 14 |
| lissen, antes | 241 s | 288 s | 19 | 3 | 21 (solo español) |
| lissen, ahora | 141 s | 152 s | 15 | 2 | 21 / 19 / 24 |
| offlinecurrencyconverter, en la primera validación | 358 s | 410 s | 30 | 7 | 54 / 64 / 55 |
| offlinecurrencyconverter, ahora | 250 s | 227 s | 27 | 6 | 54 / 63 / 55 |

Los hallazgos, su desglose, los elementos comparados y las cinco pantallas emparejadas coinciden con los de la primera validación en los tres idiomas, y el replay reproduce todas las acciones (10 de 10, 15 de 15 y 27 de 27, sin divergencias). El único hallazgo que no se repite, en offlinecurrencyconverter en ruso, es el del texto de una tasa de cambio (`0.9024`), que la app trae de la red y cambia cada día. La exploración baja un 51, un 41 y un 30 %, y el replay un 59, un 47 y un 45 %; un reinicio con su camino pasa de 35 y 51 s de mediana a unos 20. La corrida completa con tres idiomas tarda 570, 762 y 1011 s; en la primera validación, con el emulador dibujando por GPU, había tardado 1159, 1522 y 2076 s. Las cifras de "antes" de offlinecurrencyconverter son las de esa validación, las de las otras dos apps se midieron con el explorador anterior el mismo día y en el mismo emulador. Cada recorrido deja ahora en `exploration_script.json` y en `replay_report.json` cuánto tardó y en qué: toques, atrás, reinicios y volcados (rereminder: 12 toques, 3 atrás, 1 reinicio y 30 volcados en 110 s).

# Presupuesto de exploración

Problema: el recorrido estaba fijado en 5 pantallas y 8 toques por pantalla, sin forma de quitar el límite ni de acotarlo por tiempo, y nada decía por qué había terminado.

- Tres claves, y un 0 quita el límite. `exploration.maxStates` (5) y `exploration.maxClickables` (8) aceptan 0, y la clave nueva `exploration.maxMinutes` (0, sin límite) corta la exploración del idioma origen por tiempo; lo encontrado hasta entonces se conserva. Cada idioma reproduce después ese recorrido y tarda más o menos lo mismo, así que una corrida dura lo que la exploración por uno más el número de idiomas.
- El recorrido dice por qué terminó. `report.json` lleva en el idioma origen un bloque `exploration`: `stop` (`maxStates`, `maxMinutes`, `maxClickables` cuando se tocó todo lo que el límite por pantalla permite, `exhausted` cuando no quedaba nada por tocar, `relaunchFailed`), las pantallas, las actividades visitadas y las que declara el manifiesto, y el tiempo con sus toques, atrás, reinicios y volcados. Con `exhausted` la consola lo avisa: o la app es así de pequeña, o necesita contenido o una cuenta para mostrar más.
- Qué pasa al subir o quitar el límite, medido. Cinco apps exploradas sin límite de pantallas, con 8 toques por pantalla y un tope de 15 minutos:

| App | Pantallas a los 2, 5, 10 y 15 min | Qué terminó el recorrido |
|---|---|---|
| rereminder | 5, 7, 8, 13 | el tope de tiempo |
| beanbook | 5, 5, 7, 9 | el tope de tiempo; 6 de las 9 son el selector de fecha en meses distintos |
| offlinecurrencyconverter | 2, 5, 8, 11 | el tope de tiempo; de la sexta en adelante son la pantalla principal y el selector, una vez por divisa elegida |
| ambio | 3, 4, 4, 4 | a los 5 minutos, tocado todo lo que el límite por pantalla permite |
| audiobook | 3, 3, 3, 3 | a los 4 minutos, sin nada más que tocar |

Tres lecturas. Las primeras pantallas son baratas y las siguientes no: rereminder llega a 5 en minuto y medio y necesita diez minutos para llegar a 9, porque cada pantalla nueva está más lejos del inicio y cada regreso es un reinicio con un camino más largo. Quitar el límite no da cobertura donde no hay más que ver: audiobook y ambio terminan solas con 3 y 4 pantallas, las mismas que con el límite de 5. Y sin límite una app con un calendario o una lista no se acaba nunca: en beanbook dos tercios de lo encontrado son el mismo selector de fecha, mes a mes, y en offlinecurrencyconverter cada divisa que se elige de su lista da una pantalla principal y un selector "nuevos". Las actividades visitadas no sirven aquí como medida de cobertura: las cinco apps muestran todo en una sola actividad.
- Un mensaje pasajero no es una pantalla. Con el explorador más rápido apareció un caso que el anterior esquivaba por suerte: en offlinecurrencyconverter, tocar "Sync Now" escribe "Sync completed successfully" en una línea de la pantalla durante unos tres segundos (medido: aparece hacia los 3 s del toque y a los 6 s ya no está). Los dos volcados que dan una pantalla por asentada caen a los 3 y a los 6 s: medio segundo antes o después decide si el aviso entra como una pantalla nueva, y con él cambian las cinco pantallas exploradas y todos los hallazgos de la app. Ahora, cuando la pantalla tras un toque es la anterior salvo por unos pocos elementos (hasta 5 añadidos o cambiados), se vuelve a mirar dos veces, cada 2,5 s: si vuelve a ser la que era, el toque no navegó; si la diferencia sigue ahí, es una pantalla. Una pantalla que de verdad es una variación pequeña de la anterior (una pestaña, un paso de un asistente) paga esos diez segundos una vez, al encontrarla. El replay aplica la misma regla donde el origen grabó que el toque no cambiaba de pantalla.
- Valor por defecto. Se conservan 5 pantallas y 8 toques: son las pantallas baratas, es la configuración con la que se validó todo, y lo que se gana al subirlo depende de la app. Para una evaluación más honda la combinación razonable es subir `exploration.maxStates` y poner siempre `exploration.maxMinutes`, que es lo que impide que un calendario se lleve la corrida.

# Aviso cuando el idioma no llegó a la pantalla

Problema: una pantalla traducida que sale igual al origen no da hallazgos, y eso se lee como "la traducción cabe" también cuando en pantalla no se tradujo nada. La primera validación lo encontró en las apps hechas con un framework multiplataforma, y el informe no lo decía.

- Cuenta de los textos que no cambiaron (`model/ScreenTexts`). Por idioma, sobre las pantallas comparadas: cuántos textos distintos de las pantallas origen (el texto y la descripción de cada elemento que tiene una palabra; lo tecleado en un campo no cuenta) están letra por letra en las pantallas traducidas. Queda en `screenText` (`sourceTexts`, `unchanged`, `share`, `sameAsSource`). Con 5 textos o más y el 90 % sin cambiar, la consola avisa y el informe web marca el idioma ("same text as source") en el resumen y en su pestaña. Es un hecho de las pantallas, no un veredicto: una pantalla que es casi toda datos también conserva sus textos.
- También sobre evaluaciones ya hechas. `--reanalyze` lo calcula con los volcados guardados y lo pone en `summary.csv`. Sobre las 12 apps de la primera validación marca 14 de las 36 evaluaciones de idioma (tasks, locreminder y salomax en los tres idiomas, vibe y superproductivity en ruso y árabe, bonavortaro en español) y reproduce los 36 totales de hallazgos.
- El framework de la app (`helper/AppFramework`). El preflight reconoce Flutter, React Native, Capacitor, Cordova, los recursos de Compose Multiplatform, Xamarin y Unity por los archivos que dejan en el APK, lo guarda en `preflight.app.frameworks`, y la consola y el resumen del informe dicen dónde están entonces los textos. La app no se rechaza.
- Lo que el aviso destapó. De las apps marcadas, salomax no encajaba con la explicación de la primera validación: es una app sin framework y aun así salía con el texto del origen. La causa era otra y afectaba también a tasks; es la sección siguiente. Y bonavortaro en español es el caso del idioma de origen: sus textos están en esperanto, se tradujeron como si fueran inglés, y 231 de las 300 traducciones al español son el mismo texto que entró.
- Comprobado en el emulador con vibe: `frameworks` trae `capacitor`; ruso y árabe quedan marcados, con 37 de 37 textos iguales, y español no, con 8 de 37, porque la app trae su propio español. beanbook, con 58 de 125, no se marca.

# El idioma que algunas apps quitan al arrancar

Problema: salomax y tasks se evaluaron en la primera validación con pantallas en inglés en los tres idiomas, y se explicó mal en los dos casos.

- Qué pasa, medido a mano en el emulador. Tras instalar la app, `cmd locale set-app-locales` deja `[ar]` y tres segundos después sigue ahí; se lanza la app y a los cinco segundos la lista está vacía y la pantalla en inglés. Un segundo arranque, con el mismo `pm clear` y el mismo `set-app-locales`, conserva `[ar]` y la app sale en árabe. Igual en tasks con ruso. Esperar antes de lanzar no cambia nada, ni saltarse el `pm clear`: lo hace la app, y solo en su primer arranque tras instalarse.
- Por qué. Las dos declaran el servicio `AppLocalesMetadataHolderService` de AppCompat con `autoStoreLocales`; de las 15 APK de la validación son las únicas. Con esa opción AppCompat copia al sistema, la primera vez, el idioma que la app guardaba por su cuenta, que en una instalación nueva es ninguno, y marca la copia como hecha activando ese componente (se ve en `dumpsys package`, en `enabledComponents`). Ese ajuste es del paquete, no de sus datos: `pm clear` no lo borra y una desinstalación sí. ITDroid instala cada versión y fija el idioma antes del primer arranque, así que caía siempre en ese caso.
- Qué hacía eso con una evaluación. El recorrido de tasks no tiene ningún reinicio, así que sus cinco pantallas se capturaban en inglés en los tres idiomas: de ahí los 35 de 35 textos iguales y el 0 / 0 / 25, que la primera validación atribuyó a los recursos de Compose Multiplatform. En salomax las tres primeras pantallas se capturaban en inglés y, desde el primer reinicio, la app ya estaba en el idioma: el replay buscaba las pantallas que había aprendido en inglés y encontraba otras. De ahí las 13 de 30 acciones y las 17 divergencias, que la primera validación atribuyó al selector de fecha.
- Corrección (`AdbExplorer.startClean`). Los cuatro arranques limpios del explorador (el primero de la exploración y del replay, y cada reinicio) pasan por un mismo método: con la primera pantalla asentada lee el idioma que el sistema guarda para la app (`cmd locale get-app-locales`) y, si no es el pedido, repite el arranque limpio una vez. Si la app lo quita también la segunda vez, lo dice una vez y sigue: esa app se evalúa en el idioma que ella elige. Cada recorrido deja `languageRestarts` y `languageRefused` junto a sus toques y reinicios. A una app que conserva el idioma le cuesta un comando `adb` por arranque (0,05 s). Comprobado con rereminder, que no lo quita: con esta versión da lo mismo que en "Explorador más rápido" (133 / 162 / 14 con el mismo desglose, 313 elementos comparados, replay 10 de 10) y ningún arranque repetido.
- Resultado, con las dos apps evaluadas de nuevo en los tres idiomas:

| | Hallazgos es / ru / ar | Replay es / ru / ar | Texto igual al origen es / ru / ar |
|---|---|---|---|
| salomax, en la primera validación | 7 / 7 / 62 | 13/30, 13/30, 13/30 | 90 % / 90 % / 93 % |
| salomax, ahora | 52 / 48 / 46 | 29/29, 29/29, 29/29 | 0 % / 0 % / 0 % |
| tasks, en la primera validación | 0 / 0 / 25 | 8/11, 9/11, 9/11 | 100 % / 100 % / 100 % |
| tasks, ahora | 14 / 8 / 2 | 7/7, 7/7, 7/7 | 23 % / 31 % / 37 % |

Las dos apps muestran ahora el idioma en todas sus pantallas, y cada versión instalada repitió su primer arranque una vez. En tasks los textos que no cambian son nombres (Tasks.org, Microsoft To Do, Google Tasks, DAVx⁵, CalDAV, EteSync); sus hallazgos son de posición en español y ruso, y en árabe quedan 2, con 35 de 36 pares de hermanos intercambiados, donde antes eran 25 y todos de falta de espejado porque la pantalla seguía en inglés. En salomax el replay reproduce todas las acciones en los tres idiomas, sin divergencias, y el de un idioma tarda unos 290 s donde con las divergencias tardaba 532. Las acciones grabadas son 29 y 7, no 30 y 11, porque el recorrido ya no vuelve atrás al agotar el presupuesto (ver "Explorador más rápido"). De las 15 APK solo estas dos declaran `autoStoreLocales`; no se ha mirado cuántas del lote de 40 de octubre lo hacen.

# Contenido antes de explorar

Problema: una app que necesita contenido o una cuenta antes de tener algo que mostrar se evalúa por su puerta. La primera validación lo vio en audiobook (3 pantallas, las de bienvenida), lissen y tasks.

- `exploration.setup` (`helper/SetupScript`): un archivo con un comando `adb` por línea, lo que seguiría a `adb -s <dispositivo>`. Se ejecuta tras cada arranque limpio, después de `pm clear` y antes de lanzar la app, en el idioma origen y en cada idioma, para que todas las versiones partan del mismo contenido. Una línea que empieza por `#` es un comentario y un argumento con espacios va entre comillas. La primera vez se imprime lo que contestó cada comando; un archivo que no existe aborta la corrida antes de empezar.
- Hasta dónde llega. A lo que llega `adb`: un archivo en el almacenamiento compartido, una fila de un proveedor de contenido, un intent, un ajuste. No inicia sesión en una cuenta y no contesta una pantalla, ni de la app ni del sistema.
- El informe avisa cuando la exploración terminó sin nada más que tocar (`exhausted`), que es la señal de que la app no tenía más que enseñar sin contenido.
- Comprobado en el emulador que los comandos se ejecutan en cada versión, con un `push` a la carpeta de descargas y un `ls` en las cinco apps de la verificación. No se comprobó con una app que cambie sus pantallas por ese contenido. audiobook, que es el ejemplo del que salió este punto, no es ese caso: pide su carpeta con el selector de carpetas del sistema (su manifiesto no pide permiso de almacenamiento), y esa pantalla sigue necesitando a una persona.

# Cifras, idioma de origen y dispositivo

Cuatro hallazgos menores de la primera validación.

- Cifras. El modelo reescribía los números con el formato del idioma de destino. Un número con separadores (`6,568.65`, `09:41`, `2026-11-14`) viaja ahora enmascarado como un placeholder y vuelve tal cual; una respuesta que cambie una cifra se rechaza. En beanbook, `6,568.65 EUR` y `6,563.40 EUR` salían reescritos en español y en ruso (`6.568,65 EUR`, `6 568,65 евро`) y ahora salen iguales en los tres idiomas. La consecuencia es la contraria: el número ya no se adapta al idioma.
- Idioma de origen (`helper/SourceLanguageCheck`). El idioma origen sigue siendo el `defaultLng` para todas las apps, pero ahora se comprueba: entre los textos de tres palabras o más de `res/values/strings.xml`, cuántos llevan alguna de las palabras cortas más comunes de ese idioma. Con ocho frases o más y menos del 30 %, la consola y el informe avisan. bonavortaro da un 14 % de 204 frases y queda avisado; las otras cuatro apps de la verificación dan entre el 37 y el 73 %. No usa el modelo, que puede ser uno que solo traduce, y no dice en qué idioma están los textos: una primera versión que lo intentaba dio español para el esperanto, que comparte sus palabras cortas. Solo conoce inglés, español, francés, alemán, portugués e italiano; con otro idioma origen no comprueba nada.
- Un diálogo del sistema no es una pantalla de la app. Si el volcado es el diálogo de "no responde", el explorador pulsa "esperar" y vuelve a volcar; y un volcado sin ningún elemento del paquete de la app no se captura como pantalla suya.
- Dispositivo que deja de responder. Antes de cada idioma se comprueba que el dispositivo contesta; si no, se le espera dos minutos y, si no vuelve, la corrida aborta ahí con un mensaje que lo dice y con lo evaluado hasta entonces, en vez de anotar `could not install` en cada idioma que faltaba.

Los dos últimos no se vieron actuar en esa verificación: no apareció el diálogo ni se cayó el emulador. La espera al dispositivo actuó después, en la validación final. Queda sin mirar si en tasks el literal `Delete` que recibe `SymbolIcon` es un texto o el nombre de un icono.

# Reparación de RTL

Problema: la falta de espejado en un idioma de derecha a izquierda se reportaba, y la reparación que tenía la rama `repairments` del equipo original (`supportRTL`, `createLayoutsRTL`) no estaba en esta.

- Modo opcional, `rtl.repair=true` (`helper/RtlRepair`). Cada idioma de derecha a izquierda se evalúa dos veces: la app tal cual y la app reparada, con el mismo recorrido y contra el mismo origen. La reparación hace tres cosas en la app decodificada: declara `android:supportsRtl="true"`, sin lo cual Android deja todos los layouts de izquierda a derecha sea cual sea el idioma; renombra en los layouts los atributos que nombran un lado a los que nombran el inicio o el fin; y añade una copia de cada layout con la dirección forzada en su elemento raíz. Tras construir el APK reparado todo se deshace, para que el idioma siguiente parta de la app sin tocar.
- Qué cambia respecto al original. Renombraba cualquier atributo acabado en `Left=` o `Right=`, lo que convierte `nextFocusLeft` o el `contentPaddingLeft` de CardView en atributos que no existen, y la app deja de construir; aquí es una lista cerrada de atributos, a la que se añaden `layout_toLeftOf` y `layout_toRightOf`, los lados de ConstraintLayout y los valores `left` y `right` de `gravity`, que el original no tocaba. Trabajaba por líneas y se saltaba la línea entera si ya tenía un atributo de inicio o fin; aquí se mira cada elemento. Ponía las copias forzadas en `layout-ar`, que solo sirve al árabe y tapa las variantes apaisada y de tableta; aquí van a `layout-ldrtl`, el calificador de la dirección, conservando los demás calificadores de la carpeta. Con `minSdk` menor que 17 los atributos de inicio y fin se añaden junto a los de lado en vez de sustituirlos.
- En el informe. El idioma lleva `rtlRepair`: `applied` (si se añadió `supportsRtl`, layouts cambiados, atributos renombrados, copias forzadas) y los mismos campos que el idioma para la app reparada; sus archivos van en `rtlResults/<lang>` y su APK en `<paquete>-<lang>-rtl-aligned-debugSigned.apk`. La página muestra qué cambió, los hallazgos antes y después, y en cada pantalla la captura de la app reparada junto a la del origen y la de la app tal cual. El APK internacionalizado se construye con la reparación; si así no construye, sin ella, y queda dicho.
- Medido en el emulador, en árabe:

| App | Punto de partida | Reparación aplicada | Hallazgos antes y después |
|---|---|---|---|
| bonavortaro | sin `supportsRtl` | `supportsRtl`, 13 layouts, 38 atributos, 42 copias | 24 y 0 |
| salomax | con `supportsRtl` | 23 layouts, 52 atributos, 122 copias | 46 y 46 |
| tasks | con `supportsRtl` | 23 layouts, 53 atributos, 100 copias | 2 y 2 |
| beanbook | con `supportsRtl` | 2 layouts, 12 atributos, 5 copias | 105 y 105 |
| vibe | con `supportsRtl`; la interfaz es una página web | 19 layouts, 58 atributos, 56 copias | 74 y 74 |

bonavortaro es el caso que la reparación arregla entero: la app no declaraba `supportsRtl`, sus pantallas en árabe tenían el texto en árabe y todo lo demás en su sitio de siempre, y con la reparación las capturas muestran la pantalla espejada, los 10 pares de hermanos revisados se intercambian y el replay reproduce 44 de 44 acciones. En las otras cuatro no cambia nada, y el informe lo enseña así. Las cuatro ya declaraban `supportsRtl`. En vibe los layouts que hay para reparar no son la página que el usuario ve. beanbook ya intercambiaba 500 de sus 604 pares de hermanos y solo 30 de sus 105 hallazgos llevan falta de espejado. Los 46 hallazgos de salomax son los 16 botones de su teclado numérico, que la app mantiene de izquierda a derecha también tras la reparación, y los 30 días del calendario del sistema, que en árabe empieza la semana en sábado; ninguno es un layout que reparar. Las cinco apps reparadas construyen, arrancan y reproducen su recorrido entero.
- Límites. La reparación actúa sobre el manifiesto y los layouts XML. No hace nada por una interfaz que no está hecha de layouts de Android, ni por una vista que coloca a sus hijos con su propia aritmética, ni por un layout que la app infla desde código con lados fijos. Y un hallazgo de falta de espejado puede ser correcto por diseño (un teclado numérico, los controles de reproducción): la reparación no distingue esos casos de los demás.

# Detalles del informe web

Lo que se vio al leer los informes de esta fase.

- Recuadros fuera de sitio en los diálogos. En una pantalla que es un diálogo (el inicio de sesión de tasks, el selector de fecha de salomax) los recuadros de los elementos salían más abajo y más grandes que los elementos de la captura. El volcado guardado era correcto: lo que fallaba era la página, que tomaba como tamaño de la pantalla lo más lejos que llegaba el volcado de esa misma pantalla. El de un diálogo solo llega a los bordes del diálogo (960 × 1636 en el de tasks, en una pantalla de 1080 × 2400), así que cada recuadro se dibujaba 1,13 veces más a la derecha y 1,47 veces más abajo. Ahora el tamaño es el de la captura, que es la pantalla entera, y mientras carga, lo más lejos que llega cualquier volcado de la exploración. Además los recuadros se colocan sobre la imagen sola: antes la franja del pie de foto entraba en la cuenta y los bajaba un 3 %. De 104 pantallas origen guardadas en las corridas de esta fase y de la primera validación, 22 son diálogos, en salomax, beanbook y tasks. Solo afectaba al dibujo; los hallazgos no cambian.
- El mismo supuesto estaba en el comparador, donde decide si un elemento con un hallazgo quedó "espejado" (a la misma distancia del borde derecho que tenía del izquierdo) y dónde buscar la pareja de un elemento en un idioma de derecha a izquierda. Ahora el ancho es el de la pantalla más ancha de las dos exploraciones. Re-analizadas las 53 evaluaciones de idioma guardadas, no cambia ninguna cifra ni ningún hallazgo; queda cubierto por una prueba con un diálogo hecho a mano.
- Separadores. Los textos del informe que separaban sus partes con un punto medio usan comas, y la prueba de la página comprueba que el código de la página no escribe ninguno. Se mira en el código y no en lo que la página muestra, porque un texto de una app puede traer el suyo: beanbook tiene uno.
- La pestaña de rendimiento se llama "ITDroid performance" y empieza diciendo qué mide: lo que le costó a ITDroid evaluar la app, no el rendimiento de la app traducida.
- Pruebas de la fase. La suite pasa de 230 a 264 pruebas y la de la página de 74 a 91 comprobaciones.

# Validación final

Con todo lo anterior hecho, la validación se repitió entera con una sola versión: el jar que sale de compilar este código. Las mismas quince APK, en el emulador (Android 13, API 33, x86_64), en español, ruso y árabe, con PLaMo Translate y todos los valores por defecto (`hardcoded.translate=ui`, 5 pantallas, 8 toques por pantalla, sin reparación de RTL), en una pasada de cuatro horas y cuarto. fit_book, counted y oxu se rechazaron en el preflight en menos de un segundo, porque solo traen código nativo arm64. Las otras 12 se evaluaron en los tres idiomas: 36 evaluaciones de idioma, las 36 con resultado. La fila de audiobook es de una repetición con el mismo jar, porque en la pasada el emulador dejó de responder mientras se exploraba. Y los datos del emulador se habían recreado ese mismo día, así que corrió con la imagen de fábrica, lo que cambia las cifras de las dos apps que son una página web. Las dos cosas se explican más abajo.

| App | Interfaz | Pantallas | Hallazgos es / ru / ar | Replay es / ru / ar | Texto igual al origen es / ru / ar | Hardcoded (traducidos) | Minutos |
|---|---|---|---|---|---|---|---|
| com.jbgsoft.ambio | Android | 4 | 96 / 67 / 16 | 40/40, 40/40, 39/40 | 30 % / 0 % / 0 % | 13 (0) | 22 |
| com.offlinecurrencyconverter.app | Android | 5 | 54 / 63 / 55 | 27/27, 27/27, 27/27 | 47 % / 47 % / 47 % | 20 (0) | 18 |
| com.olaf.rereminder | Android | 5 | 133 / 162 / 14 | 10/10, 10/10, 10/10 | 5 % / 2 % / 2 % | 42 (0) | 11 |
| com.superproductivity.superproductivity | Capacitor (página web) | 5 | 67 / 0 / 75 | 46/49, 48/49, 48/49 | 27 % / 100 % / 100 % | 537 (20) | 44 |
| com.zaifears.locreminder | Flutter | 5 | 0 / 0 / 10 | 16/16, 16/16, 16/16 | 100 % / 100 % / 100 % | 130 (0) | 12 |
| de.ph1b.audiobook | Android | 3 | 0 / 0 / 0 | 22/22, 22/22, 22/22 | 0 % / 0 % / 0 % | 4 (0) | 17 |
| de.salomax.currencies | Android | 5 | 52 / 48 / 46 | 29/29, 29/29, 29/29 | 0 % / 0 % / 0 % | 19 (0) | 27 |
| dev.fiedri.vibe | Capacitor (página web) | 5 | 148 / 0 / 83 | 12/12, 12/12, 12/12 | 21 % / 100 % / 100 % | 13 (0) | 21 |
| io.github.kotchwane.bonavortaro | Android | 5 | 4 / 15 / 24 | 43/44, 44/44, 43/44 | 96 % / 70 % / 52 % | 15 (0) | 36 |
| io.github.pintofi.beanbook.expert | Android | 5 | 149 / 132 / 105 | 10/10, 10/10, 10/10 | 46 % / 46 % / 46 % | 53 (21) | 11 |
| org.grakovne.lissen | Android | 5 | 21 / 19 / 24 | 15/15, 15/15, 15/15 | 5 % / 5 % / 5 % | 201 (0) | 17 |
| org.tasks | Android y recursos de Compose Multiplatform | 5 | 14 / 8 / 2 | 7/7, 7/7, 7/7 | 23 % / 31 % / 37 % | 2710 (9) | 15 |

"Interfaz" dice dónde está el texto de la app; `Android` son los recursos y el código Dalvik, que es lo que ITDroid traduce. "Replay" son las acciones del recorrido grabado que se pudieron reproducir. "Texto igual al origen" es el `screenText` del informe: la parte de los textos distintos de las pantallas origen que aparece letra por letra en las pantallas traducidas; es alta cuando no se tradujo nada, y también cuando la pantalla son datos, como una lista de divisas. "Minutos" es lo que duró la corrida de ITDroid con los tres idiomas.

| | es | ru | ar |
|---|---|---|---|
| Hallazgos | 738 | 514 | 454 |
| Pantallas comparadas, de 57 grabadas | 57 | 57 | 57 |
| Elementos comparados | 2701 | 2705 | 2697 |
| Cambiaron de sitio o de tamaño | 437 | 361 | 234 |
| Siguen igual, cambió un vecino | 297 | 153 | 211 |
| Componentes perdidos | 4 | 0 | 8 |
| Componentes añadidos | 0 | 0 | 1 |
| Hallazgos con falta de espejado | no aplica | no aplica | 324 |
| Acciones del replay reproducidas, de 281 | 277 | 280 | 278 |
| Evaluaciones marcadas con el texto del origen, de 12 | 2 | 3 | 3 |

En árabe se revisaron 2085 pares de elementos hermanos y 678 no se intercambiaron.

Lo que quedó comprobado:

- El flujo completo en las 12 apps: decodificar, analizar, traducir, reconstruir, instalar, explorar, reproducir en cada idioma, comparar y cerrar. Las 12 terminan en `finished` y con su APK internacionalizado construido. La mediana es de 17 minutos por app con tres idiomas (entre 11 y 44).
- Reconstrucción. Cinco de las 12 (rereminder, locreminder, audiobook, lissen y tasks) solo se construyen con los ajustes automáticos de la sección "Construcción con apktool", entre uno y nueve por app, que quedan en `buildAdjustments`.
- Hardcoded con la política `ui`. En 9 de las 12 apps ningún literal del código llega a la interfaz por una llamada que la tabla reconozca, y no se traduce ninguno: los que hay van a logs, datos y llamadas de librería. En las otras tres se movieron a recursos y se tradujeron: beanbook, 5 literales del código y 16 del XML; tasks, 9 del código; superproductivity, 20 del código en 23 lugares. Los recursos `itdroid_code_*` e `itdroid_hc_*` están en el APK internacionalizado de las tres, los 50 con su traducción a los tres idiomas (comprobado con `aapt2 dump resources`), y las tres apps construyen, arrancan y se reproducen con la clase que ITDroid les añade. Siguen sin verse en pantalla: son textos de notificaciones, de widgets de escritorio y de errores de inicio de sesión, adonde la exploración no llega.
- Traductor. 3366 textos enviados, 37 respuestas descartadas por los controles y 17 textos que se quedaron en el idioma origen. 35 de los 37 descartes son de bonavortaro, cuyos textos por defecto están en esperanto; el informe lo avisa ahora (14 % de 204 frases parecen inglés). Las cifras ya no se reescriben: `6,568.65 EUR` sale igual en los tres idiomas. Los deslices de sentido del modelo son los mismos (`Balances` sigue saliendo `Equilibro`).
- Comparador. Las 57 pantallas grabadas se compararon en los tres idiomas y ninguna quedó en `statesNotCompared`. En las 36 evaluaciones `amIPFs` es la suma de sus cuatro partes y coincide con la lista de hallazgos.
- Replay. 9 de las 12 apps reproducen todas las acciones en los tres idiomas, sin divergencias. En las otras tres se capturaron y compararon igualmente todas las pantallas: ambio en árabe (39 de 40) y bonavortaro en español y árabe (43 de 44) no encuentran un elemento de la primera pantalla, como en la primera validación; y superproductivity tiene un reinicio que no vuelve a una de sus pantallas en ninguno de los tres idiomas (48 de 49, y 46 en español).
- El idioma en el primer arranque. salomax y tasks repitieron su primer arranque una vez en cada versión instalada (`languageRestarts: 1`) y muestran el idioma en todas sus pantallas: 0 de 42 textos iguales al origen en salomax, y 8, 11 y 13 de 35 en tasks, sobre todo nombres propios. Ninguna otra app lo necesitó.
- Aviso de texto igual al origen. Marca 8 de las 36 evaluaciones: locreminder en los tres idiomas (24 de 24 textos), vibe y superproductivity en ruso y árabe (38 de 38 y 33 de 33) y bonavortaro en español (22 de 23). Las tres primeras son las apps cuya interfaz no está en los recursos de Android, y el informe nombra su framework; la cuarta es la del idioma de origen.
- Dispositivo que deja de responder. A la hora y diez minutos de la pasada el emulador dejó de contestar, durante la exploración de audiobook. ITDroid conservó las tres pantallas ya capturadas, esperó al dispositivo los dos minutos previstos antes del primer idioma y cerró la corrida como `aborted` con un mensaje que lo dice: es la espera de "Cifras, idioma de origen y dispositivo", que no se había visto actuar. Hubo que arrancar el emulador de nuevo (seis minutos) y las siete apps que faltaban corrieron sin incidentes. audiobook, repetida sola, terminó en 17 minutos.
- Privacidad. Ningún archivo que ITDroid escribe en las 12 carpetas contiene el nombre de usuario ni una ruta absoluta (180 archivos de texto revisados), y todos los informes llevan `apkSha256`.

Qué cambió respecto a la primera validación:

| App | Primera validación | Validación final | Qué lo explica |
|---|---|---|---|
| ambio, rereminder, bonavortaro, beanbook, lissen | 96 / 67 / 16, 133 / 162 / 14, 4 / 15 / 24, 149 / 132 / 105, 21 / 19 / 24 | las mismas cifras | no les tocaba ningún cambio |
| salomax | 7 / 7 / 62, replay 13 de 30 | 52 / 48 / 46, replay 29 de 29 | el idioma se fija de nuevo tras el primer arranque |
| tasks | 0 / 0 / 25, replay 8, 9 y 9 de 11 | 14 / 8 / 2, replay 7 de 7 | lo mismo |
| locreminder | 0 / 0 / 29, replay 9, 16 y 12 de 18 | 0 / 0 / 10, replay 16 de 16 | esta vez el recorrido se reprodujo entero |
| vibe | 134 / 0 / 74 | 148 / 0 / 83 | el WebView del emulador, con los datos recién recreados, expone más elementos |
| superproductivity | 59 / 0 / 69 | 67 / 0 / 75 | lo mismo |
| audiobook | 0 / 2 / 0 | 0 / 0 / 0 | un elemento capturado fuera de su sitio en la primera |
| offlinecurrencyconverter | 54 / 64 / 55 | 54 / 63 / 55 | una tasa de cambio que llega de la red |

- Lo que no debía cambiar no cambió. ambio, rereminder, bonavortaro, beanbook y lissen dan los mismos hallazgos en los tres idiomas, pantalla por pantalla y con el mismo desglose por tipo, y los mismos elementos comparados. El recorrido grabado es más corto en cuatro de ellas, porque el explorador ya no vuelve atrás al agotar el presupuesto, y las cinco tardan menos.
- salomax y tasks. Es la corrección de "El idioma que algunas apps quitan al arrancar", y sus cifras son las que esa sección midió. En árabe salomax intercambia ahora 295 de sus 495 pares de hermanos, donde antes 1, y tasks 35 de 36, donde antes ninguno: entonces sus pantallas estaban en inglés.
- locreminder. En árabe pasa de 29 a 10 hallazgos, todos de falta de espejado, porque esta vez el recorrido se reprodujo entero en los tres idiomas; en la primera validación sus cifras iban con lo que se lograba reproducir en cada pasada. Es una sola corrida: no basta para decir que su recorrido ya se repite siempre.
- vibe y superproductivity. Son las dos apps cuya pantalla es una página web, y las únicas en las que cambió lo que el volcado ve: cada pantalla trae uno o dos elementos más, varios elementos traen un nombre que antes venía vacío (`Open menu`, `Open filter/group/sort menu`, `Project Notes`, `Super Productivity`) y la página se dibuja unos píxeles más ancha. Hay más que comparar (278 elementos en vez de 269 en vibe, 229 en vez de 223 en superproductivity) y salen más hallazgos; en superproductivity, además, el recorrido grabado tiene 49 acciones en vez de 43. No lo cambió ITDroid sino el dispositivo: el jar con el que vibe había dado 134 hallazgos en español el día anterior da 148 en este emulador, los mismos que el jar final y con los mismos 278 elementos. Los datos del emulador se habían recreado esa mañana: sus archivos de datos son de ese día y todos sus paquetes son los de fábrica, con WebView 109.0.5414.123. Qué tenía distinto el WebView del emulador anterior ya no se puede saber, porque su versión no se anota en ningún informe. Dentro de un mismo dispositivo las cifras de estas apps sí se repiten: en la primera validación superproductivity dio 59 / 0 / 69 dos veces, con el emulador dibujando por GPU y por software. Por lo mismo, los 74 hallazgos de vibe en "Reparación de RTL" y sus 37 textos en "Aviso cuando el idioma no llegó a la pantalla" son del emulador anterior; en este son 83 y 38.
- audiobook. Los 2 hallazgos en ruso de la primera validación eran un elemento de la segunda pantalla capturado 8 píxeles a la derecha de su sitio; ahora sale donde está en el origen. El recorrido graba 22 acciones donde entonces 7, que son las 7 primeras de ahora: aquella exploración se quedó ahí y esta sigue hasta que no queda nada por tocar (`exhausted`), con las mismas 3 pantallas. En las dos validaciones el emulador tuvo problemas mientras se evaluaba esta app, cuyos toques abren el selector de carpetas del sistema (en la primera, el diálogo de "System UI isn't responding"; en esta dejó de contestar), y evaluada sola terminó sin incidentes; no se investigó si lo provoca la app.
- offlinecurrencyconverter. Un hallazgo menos en ruso: es el texto de una tasa de cambio, que la app trae de la red.
- Replay y pantallas. Se reproducen 277, 280 y 278 de 281 acciones (entre el 98,6 y el 99,6 %), donde antes 254, 263 y 255 de 284 (entre el 89 y el 93 %). Y las 57 pantallas grabadas se comparan en los tres idiomas, donde antes faltaba una en español y otra en árabe.
- Totales. Los hallazgos suben en español y ruso (de 657 a 738 y de 468 a 514) porque salomax y tasks muestran ahora el idioma, y en español también por las dos apps web. En árabe bajan (de 497 a 454) por las mismas dos apps y por locreminder: los hallazgos con falta de espejado pasan de 345 a 324 y los pares de hermanos sin intercambiar de 1003 a 678.
- Aviso de texto igual al origen. 8 evaluaciones marcadas, donde el re-análisis de la primera validación marcaba 14: salomax y tasks ya no lo están.
- Tiempo. Las corridas de las 12 apps suman 4,1 horas, donde antes 7,6; la mediana baja de 31 a 17 minutos y la más lenta de 100 a 44. Las 12 tardan menos: salomax pasa de 100 minutos a 27 porque ya no diverge, bonavortaro de 64 a 36 y superproductivity de 57 a 44. Los tiempos son en las dos la duración de la corrida de ITDroid; el 35 de mediana que se dio tras la primera validación era el tiempo del lote, con sus esperas al emulador.

Comparación con los lotes de octubre. Solo tres de las 15 apps tenían antecedente, en otra versión: ambio se evaluaba y se evalúa; superproductivity, que tardó 70 minutos una vez y agotó un límite de 110 otra, tarda ahora 44; y audiobook pasa de no reconstruirse a evaluarse. Las otras 12 no estaban en esos lotes, y las cifras de entonces no son comparables con las de después de la sección "Comparador de layouts".

# Límites conocidos y trabajo pendiente

Límites de una evaluación, hoy:

- Solo se traduce lo que está en los recursos de Android y en los literales del código Dalvik de la app. El texto de una app web (Capacitor, Cordova), de Flutter, de React Native o de los recursos de Compose Multiplatform no se traduce; el informe nombra el framework y marca los idiomas cuyas pantallas muestran el texto del origen.
- En una app cuya interfaz es una página web, lo que el volcado ve depende del WebView del dispositivo: tras recrear los datos del emulador cambiaron los elementos que expone y, con ellos, las cifras (ver "Validación final"). Dentro de una corrida el origen y los idiomas se capturan en el mismo dispositivo y se comparan bien; las cifras de dos dispositivos no son comparables entre sí.
- La exploración solo ve lo que la app enseña por sí sola. `exploration.setup` deja en el dispositivo lo que se puede dejar con `adb`; no inicia sesión ni contesta una pantalla, así que una app que pide una carpeta en el selector del sistema o una cuenta se sigue evaluando por su puerta. `exploration.fillForms` solo escribe en los campos de texto que encuentra.
- El presupuesto por defecto es pequeño: 5 pantallas y 8 toques por pantalla. Se sube o se quita en una línea, y cada pantalla de más cuesta más que la anterior (ver "Presupuesto de exploración").
- El replay supone que la app se comporta igual en cada arranque. Donde no (contenido que llega de la red, un mapa que tarda, un diálogo de primer uso) el recorrido diverge, las pantallas no reproducidas no se comparan y las cifras varían entre corridas.
- Un hardcoded que se muestra fuera de las pantallas de la app (notificación, widget) se traduce y se construye, pero su layout no se compara.
- Con `hardcoded.translate=ui` no se traduce el texto que llega a la pantalla a través de objetos de datos de la app; `ui+unknown` lo alcanza y traduce también algunos datos.
- El idioma de origen es el mismo para todas las apps (`defaultLng`); solo se detecta si esa línea falta. Se avisa cuando los textos por defecto no parecen estar en él, para seis idiomas origen, y no se corrige.
- La traducción es tan buena como el modelo: sentido equivocado en palabras sueltas, y nombres propios traducidos si no están en `protected-terms.txt`. Las cifras se conservan tal cual, lo que también quiere decir que no se adaptan al idioma.
- Una app con solo código nativo ARM no corre en un emulador x86_64. Una app que apktool no reconstruye, fuera de los dos casos que ITDroid ajusta, se rechaza.
- La falta de espejado en un idioma de derecha a izquierda se reporta y, con `rtl.repair=true`, se evalúa además la app reparada. La reparación llega al manifiesto y a los layouts XML, no más allá.
- Una app que quita su idioma en cada arranque, no solo en el primero, se evalúa en el idioma que ella elige; la consola y `languageRefused` lo dicen. No se ha visto ninguna.
- Un proceso matado desde fuera no puede escribir su estado: el informe queda en `running` y es el servidor del informe web quien lo muestra como interrumpido.

No verificado en dispositivo: el gancho de cierre con un Ctrl+C real en la consola (se probó matando el proceso); el caso en que el APK internacionalizado no se construye; los hardcoded traducidos de la validación vistos en pantalla; el cierre del diálogo de "no responde", que no llegó a darse (la espera a un dispositivo que deja de contestar sí actuó en la validación final); `exploration.setup` con una app cuyas pantallas dependan del contenido que se le deja; y un dispositivo físico o ARM, porque todo se corrió en un emulador x86_64.

Trabajo pendiente, por orden de lo que más cambiaría la lectura de un resultado:

- Contenido que `adb` no puede dejar: contestar el selector de carpetas del sistema o iniciar sesión antes de explorar, con un guion de toques por app o credenciales de prueba.
- Traducir los formatos que hoy no se tocan, empezando por los JSON de textos de una app web, que serían lo más abordable.
- Detectar el idioma de origen por app, en vez de avisar de que no coincide.
- Anotar en el informe la versión del WebView del dispositivo: de ella dependen las cifras de una app que es una página web, y hoy no queda registrada.
- Averiguar si es audiobook quien hace fallar al emulador: tuvo problemas con ella en las dos validaciones y no al evaluarla sola.
- Mirar si alguna app del lote de 40 de octubre declara `autoStoreLocales`: sus cifras de entonces tendrían el mismo error que las de salomax y tasks.

# ITDroid Arquitectura

Cómo está armado el código: qué hace cada pieza, cómo fluyen los datos y dónde tocar para extenderlo.

En una frase: ITDroid toma un APK en un idioma, traduce sus textos (recursos y hardcodeados) a otros idiomas con un LLM local, reconstruye un APK por idioma, lo explora en un dispositivo real vía `adb`, compara los layouts entre idiomas y reporta los IPFs (defectos visuales que solo aparecen al traducir).

# Pipeline end-to-end

El orquestador es `ITDroid.runITDroid`. El flujo:

1. Asegura el servidor LLM (`LLMServerManager.ensureRunning`).
2. Decodifica el APK con apktool a `temp/` (`APKToolWrapper.openAPK`).
3. Detecta textos UI hardcodeados en el smali (`HardcodedStringFinder`).
4. Traduce los recursos: `strings.xml`, `plurals.xml`, `arrays.xml` (`Translator` + `LLMTranslator`).
5. Construye el APK por defecto y guarda un backup del original (`APKToolWrapper.buildAPK`).
6. Explora el idioma origen (`ExplorationHelper.exploreDefaultLanguage`).
7. Por cada idioma destino: traduce los labels hardcodeados, parchea el smali (`SmaliPatcher`), reconstruye un APK propio, lo explora y lo compara contra el origen para hallar IPFs (`LayoutGraphComparision`).
8. Reinstala el APK original en el dispositivo (`AdbExplorer.installApk`), así el dispositivo siempre termina con la app original.
9. Escribe `report.json` e `ipfs.csv`.

El LLM se habla por HTTP OpenAI-compatible (Ollama o llama.cpp). El dispositivo se maneja por `adb` (install, locale, dump, tap). apktool y uber-apk-signer viven en `extra/`.

# Mapa de paquetes y clases

Paquete raíz: `uniandes.tsdl.itdroid`.

- `ITDroid`: punto de entrada y orquestador de todo el pipeline.

Paquete `translator/` (traducción, patrón Strategy):

- `TranslationInterface`: interfaz `translate(xmlPath, in, out)`.
- `Translator`: contexto Strategy que envuelve la estrategia.
- `LLMTranslator`: estrategia real; habla con el LLM por HTTP OpenAI-compatible.

Paquete `explorer/` (exploración del app en el dispositivo, sin root):

- `AdbExplorer`: install, locale por-app, DFS (dump/tap/back), emite `result.json`.

Paquete `helper/` (infraestructura):

- `RunConfig`: lee el archivo de config (parámetros y motor de traducción).
- `LLMServerManager`: arranca o verifica el servidor LLM.
- `APKToolWrapper`: decodifica (`apktool d`) y reconstruye+firma (`apktool b` + signer).
- `HardcodedStringFinder`: detecta textos UI hardcodeados en el smali.
- `SmaliPatcher`: reescribe los `const-string` del smali con las traducciones.
- `ExplorationHelper`: lanza `AdbExplorer` por versión de idioma.
- `LanguageBundle`: lee `settings.properties` (idiomas seleccionados).
- `NotTranslatableStringsDictionary`: strings que no se deben traducir.
- `Helper`: singleton con el directorio de trabajo y el nombre de paquete.
- `XMLComparator`: utilidad de comparación XML.
- `ExplorationException`, `ITDroidException`: excepciones.

Paquete `model/` (grafos y comparación, detección de IPFs):

- `LayoutGraph`: parsea `result.json` a `List<State>` + `List<Transition>`.
- `State`: una pantalla; convierte su `rawXML` en nodos y un grafo de relaciones espaciales.
- `AndroidNode`: un elemento UI (clase, bounds, texto, enabled).
- `AndroidNodeProperty`: enum de propiedades de nodo.
- `GraphEdgeType`: enum de relaciones espaciales (LEFT/RIGHT/ABOVE/BELOW/alineación).
- `Transition`, `TransitionType`: aristas del grafo (navegaciones).
- `LayoutGraphComparision`: empareja estados y compara grafos, produce IPFs.
- `IPF`: un Internationalization Presentation Failure.
- `IPFComparator`: ordena IPFs por severidad.

Dependencias externas (no van en el repo, ver README): apktool.jar y uber-apk-signer.jar en `extra/`, adb (Android SDK) y un servidor LLM local. Librerías Maven: `json-simple`, `commons-io`, `jdom2`.

# Las fases en detalle

# Fase 0, setup

Lee los parámetros de entrada. Hay dos formas (ver README):

- Archivo de config (recomendado): un único argumento `itdroid.config.properties` que parsea `RunConfig`. Su bloque `translation.*` elige el motor (Ollama, llama.cpp o endpoint remoto con token) e inyecta los `ITDROID_LLM_*` como system properties. El `autoStart` cumple el papel del 8º argumento.
- Argumentos posicionales: los 7 obligatorios más el 8º (LLM) opcional.

`LLMServerManager.ensureRunning` garantiza que el LLM esté accesible antes de traducir; si no, aborta con un mensaje claro. Con un token (motor `api`) omite el health-check local. Luego `APKToolWrapper.openAPK` decodifica el APK a `temp/`.

# Fase 1, traducción de recursos

`ITDroid` arma las rutas y, por cada idioma, crea un `Translator` con un `LLMTranslator` inyectado (Strategy: `Translator` no sabe cómo se traduce). `LLMTranslator.translate`:

- Traduce los `<string>` en lotes JSON, preservando placeholders (`%1$s`, `%d`).
- Traduce los `<plurals>` y `<string-array>` del `strings.xml` y de archivos hermanos del mismo `values/` (`plurals.xml`, `arrays.xml`), preservando `quantity`.
- Escribe el resultado en `temp/res/values-<lang>/strings.xml`.

Respeta `NotTranslatableStringsDictionary` y un guard de no traducir origen a origen.

# Fase 2, APK por defecto y backup

Construye el APK por defecto (idioma origen) y, si hay hardcoded, guarda una copia pristina `<pkg>-original-aligned-debugSigned.apk`. El original nunca se parchea.

# Fase 3, APK por idioma (textos hardcodeados)

Por cada idioma destino (`ITDroid.buildLanguageApk`):

1. `HardcodedStringFinder.collectUiStrings` detectó los literales UI en el smali (escanea todos los `smali*`, filtra ruido de Compose, IDs, URLs, fechas).
2. `LLMTranslator.translateUiLabels` los traduce dejando intactos nombres propios, emails, URLs, códigos.
3. `SmaliPatcher.patch` reescribe los `const-string` (escapando no-ASCII a `\uXXXX`).
4. `APKToolWrapper.buildAPK` reconstruye y firma `<pkg>-<lang>-aligned-debugSigned.apk`.
5. Se escribe `hardcoded_<lang>.csv` (original;traducido;changed) para auditar.
6. `SmaliPatcher.restoreOriginal` deja el smali pristino para el siguiente idioma.

# Fase 4, exploración

Por cada versión (origen y cada idioma), `AdbExplorer.explore`:

1. `install` (desinstala + instala el APK de ese idioma) y `cleanReset` (`pm clear`, sin root), luego fija el locale por-app (`cmd locale set-app-locales`, Android 13+).
2. `launch` (monkey) y espera a estar en primer plano (`inApp`).
3. DFS in-place: captura el estado con `stableDump` (vuelca hasta que la UI deja de cambiar estructuralmente), extrae clickables, los toca, recursa en estados nuevos y vuelve con el botón atrás.
4. Firma estructural por `resource-id` (ignora el texto), así los grafos de cada idioma son comparables.
5. Emite `result.json` (estados, transiciones, screenshots), el contrato que parsea el modelo.

# Fase 5, comparación y detección de IPFs

`LayoutGraph` parsea cada `result.json` a `List<State>`. Cada `State` convierte su `rawXML` en `AndroidNode`s y arma un grafo de relaciones espaciales (`GraphEdgeType[][]`: qué nodo está a la izquierda/derecha/arriba/abajo de cuál). `LayoutGraphComparision`:

- `pairStates`: empareja posicionalmente el estado i del grafo origen con el i del idioma, mientras `State.compareTo` los considere la misma pantalla. Los que no casan quedan sin emparejar.
- `compareStates`: para cada pantalla emparejada, compara las relaciones espaciales nodo a nodo; si una relación se pierde o aparece, ese nodo es un IPF.

Genera los IPFs, los agrupa y ordena (`IPFComparator`) y los vuelca a `report.json` e `ipfs.csv`.

# Fase 6, cierre

Restaura el APK por defecto desde el backup. `AdbExplorer.installApk` reinstala el APK original (1er argumento), así el dispositivo siempre termina con la app original. Escribe `report.json`.

# El servidor LLM y el 8º argumento

ITDroid es agnóstico al motor: solo habla un endpoint HTTP OpenAI-compatible. El 8º argumento decide cómo se provee:

- Sin espacios (ej. `qwen2.5:3b`): modo modelo Ollama. Arranca `ollama serve` si está caído, hace `ollama pull` (descarga si falta) y fija endpoint y modelo vía system properties.
- Con espacios (ej. `llama-server -m m.gguf --port 8080`): modo comando. Ejecuta el comando vía shell y espera al endpoint de `.env`.
- Vacío: pre-flight; el server debe estar arriba o aborta con mensaje claro.

Un server que ITDroid arrancó se apaga al final (shutdown hook, mata el árbol de procesos). Uno que ya estaba corriendo se reutiliza y no se toca. La salida del server va a `<output>/llm-server.log`.

# Artefactos que se generan

- `temp/`: APK decodificado (`smali*/`, `res/values*/`, `AndroidManifest.xml`); se regenera en cada run.
- `<output>/<pkg>-aligned-debugSigned.apk`: APK por defecto (idioma origen).
- `<output>/<pkg>-original-aligned-debugSigned.apk`: backup pristino del original.
- `<output>/<pkg>-<lang>-aligned-debugSigned.apk`: APK por idioma (hardcoded traducidos).
- `<output>/{trnsResults,noTrnsResults}/<lang>/result.json`: grafo explorado.
- `<output>/.../<lang>/{graph.txt, graph.json, screenshots/}`: volcados legibles y capturas.
- `<output>/report.json`: reporte final (idiomas, conteos, IPFs por idioma).
- `<output>/ipfs.csv`: IPFs en CSV.
- `<output>/hardcoded_<lang>.csv`: auditoría de los textos hardcodeados traducidos.
- `<output>/llm-server.log`: salida del servidor LLM (si ITDroid lo arrancó).

# Decisiones de diseño clave

- Strategy en la traducción (`TranslationInterface`): cambiar de IBM Watson a un LLM local fue cambiar la estrategia, sin tocar el orquestador.
- LLM agnóstico al motor: HTTP OpenAI-compatible, sirve Ollama, llama.cpp o LM Studio sin cambiar código.
- Exploración sin root: locale por-app (`cmd locale set-app-locales`) y `pm clear` no requieren root, funciona en emuladores Play Store y dispositivos físicos.
- APK por idioma: los textos hardcodeados viven en el código, así que traducirlos exige parchear el smali y reconstruir un APK por idioma.
- Firma estructural más `stableDump`: hace que las exploraciones de cada idioma sean comparables y reproducibles.
- Original intacto: backup del archivo más reinstalación del original al final (archivo y dispositivo).

Para el historial de cambios ver REFACTOR.md. Para cómo ejecutar ver README.md.

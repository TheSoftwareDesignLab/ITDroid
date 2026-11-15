# ITDroid Arquitectura

Cómo está armado el código: qué hace cada pieza, cómo fluyen los datos y dónde tocar para extenderlo.

En una frase: ITDroid toma un APK en un idioma, comprueba que se puede procesar, inventaría todos sus textos (recursos y hardcodeados) con su origen, explora la app original en el dispositivo grabando el recorrido, y por cada idioma destino traduce el inventario con un LLM local, reconstruye un APK, lo instala, repite el recorrido grabado, lo desinstala y compara los layouts contra el origen para reportar los IPFs (defectos visuales que solo aparecen al traducir).

# Pipeline end-to-end

El orquestador es `ITDroid.runITDroid`. El flujo:

1. Preflight de dispositivo y LLM (`Preflight`, `LLMServerManager`): dispositivo con API 33+ y sus ABIs, código nativo del APK compatible, servidor LLM accesible. Un fallo se informa de inmediato, pero la corrida solo se detiene después del paso 2, que no necesita ninguno de los dos.
2. Decodificación y análisis estático: apktool decodifica a `temp/`; `SmaliScan` recorre una vez el smali de la app y `XmlLiteralFinder` su XML; `HardcodedStringFinder` escribe `hcs.txt`; `XMLComparator` deja qué strings le faltan a cada idioma; `StringInventory` reúne todos los `<string>`, ítems de `<plurals>` y `<string-array>` de `res/values/*.xml` y los literales hardcoded, cada uno con su origen y con la razón por la que no se traduce si aplica, en `strings_inventory.json` (si ya existía uno en la carpeta de salida, sus traducciones se reutilizan). Con `--static` la corrida termina aquí.
3. Preflight de la app (`Preflight.checkDecodedApp`, `AdbExplorer.checkLaunch`): manifiesto con actividad lanzable y que no sea una Trusted Web Activity, minSdk compatible, y prueba de arranque de la app ORIGINAL en el dispositivo. Cualquier fallo aborta con el motivo concreto.
4. Construcción y exploración del origen: `APKToolWrapper.buildAPK` reconstruye la app intacta; `ExplorationHelper.exploreSource` la explora con el DFS de `AdbExplorer.explore`, que graba cada acción en `exploration_script.json`, y la desinstala al terminar.
5. Por cada idioma destino (`ITDroid.processLanguage`), con la app fuera del dispositivo: traduce los recursos desde el inventario (`Translator` + `LLMTranslator.translate`), entre ellos los hardcoded movidos a recursos; traduce y parchea en el smali (`SmaliPatcher`) los literales del código que no se pudieron mover; reconstruye y firma `<pkg>-<lang>-aligned-debugSigned.apk`, lo instala y repite el recorrido grabado (`AdbExplorer.replay`), lo desinstala, y compara contra el grafo origen (`LayoutGraphComparision`). Si ese APK no arranca o crashea donde el original no, se repite con los hardcoded en el idioma origen. `report.json` se reescribe tras cada idioma.
6. Cierre: quita los parches, construye el APK internacionalizado (el de nombre por defecto: los recursos de todos los idiomas, incluidos los hardcoded movidos a recursos) y reinstala el APK original en el dispositivo (`AdbExplorer.installApk`).

El LLM se habla por HTTP OpenAI-compatible (Ollama o llama.cpp). El dispositivo se maneja por `adb` (install, locale, dump, tap). apktool y uber-apk-signer viven en `extra/`.

# Mapa de paquetes y clases

Paquete raíz: `uniandes.tsdl.itdroid`.

- `ITDroid`: punto de entrada y orquestador de todo el pipeline; escribe `report.json` tras cada hito.

Paquete `translator/` (traducción, patrón Strategy):

- `TranslationInterface`: interfaz `translate(xmlPath, in, out)`.
- `Translator`: contexto Strategy que envuelve la estrategia.
- `LLMTranslator`: estrategia real; traduce los textos del inventario por HTTP OpenAI-compatible, en lotes globales de 25 en JSON (modelos de chat) o un texto por petición con el prompt propio del modelo (`translation.style` = `translategemma`, `template` o `plamo`, con los placeholders enmascarados como `<phN>`), protege los términos que no se traducen, descarta las respuestas que no son una traducción, usa el inventario como memoria, escribe `values-<lang>/strings.xml`, traduce los labels hardcodeados y sugiere valores para formularios.

Paquete `explorer/` (exploración del app en el dispositivo, sin root):

- `AdbExplorer`: install, locale por-app (y la comprobación de que la app lo conserva al arrancar), permisos runtime, comandos de preparación (`exploration.setup`), DFS con grabación (`explore`), reproducción del guion en otra versión (`replay`), prueba de arranque (`checkLaunch`), desinstalación; emite `result.json`.

Paquete `helper/` (infraestructura):

- `RunConfig`: lee el archivo de config (parámetros, motor de traducción, opciones de exploración y preflight).
- `Preflight`: comprobaciones previas de dispositivo y APK; datos de la app decodificada (`describeDecodedApp`, sin dispositivo) y su comprobación contra el dispositivo (`checkDecodedApp`).
- `AppFramework`: con qué framework multiplataforma está hecha la app, por los archivos que deja en el APK (`libflutter.so`, `capacitor.config.json`, `assets/www/cordova.js`, el bundle de React Native, `assets/composeResources`, las librerías de Xamarin y de Unity).
- `SourceLanguageCheck`: si los textos por defecto de la app parecen escritos en el idioma origen de la corrida, contando las frases que llevan alguna de las palabras cortas más comunes de ese idioma.
- `SetupScript`: lee el archivo de `exploration.setup`, un comando `adb` por línea.
- `RtlRepair`: repara la app decodificada para un idioma de derecha a izquierda (`supportsRtl`, atributos de lado a inicio y fin, copias de los layouts con la dirección forzada) y sabe deshacerlo.
- `StringInventory`: inventario de strings con origen, reglas de "no traducir", uso de cada literal hardcoded y memoria de traducciones (`strings_inventory.json`).
- `LLMServerManager`: arranca o verifica el servidor LLM.
- `APKToolWrapper`: decodifica (`apktool d`) y reconstruye+firma (`apktool b` + signer). Si la construcción falla por un atributo `android:` que apktool no conoce o por un atributo de la app cuya declaración se perdió al decodificar, lo ajusta en `temp/` y reintenta (`buildAdjustments`).
- `AppCode`: localiza el código propio de la app (raíces de paquete) a partir del `applicationId`, de la clase `BuildConfig` y de las clases que declara el manifiesto, descartando librerías.
- `SmaliScan`: una sola pasada por el smali de la app; cada `const-string` con su dex, clase, método, línea, registro, literal y el uso que le da el código (`Consumer`).
- `ConsumerRules`: la tabla que dice qué hace cada API con un string, leída de `hardcoded-consumers.txt` (empaquetado junto a la clase; uno con el mismo nombre en la carpeta de settings añade reglas que se prueban primero).
- `SmaliText`: escapado y desescapado de literales de smali, compartido por detector y parcheador.
- `XmlLiteralFinder`: texto literal en atributos de layouts, menús, preferencias, grafos de navegación y manifiesto.
- `HardcodedStringFinder`: agrupa las apariciones del escaneo en literales distintos, descarta el ruido del compilador y escribe `hcs.txt` por clase y método.
- `HardcodedPolicy`: qué usos de un literal del código se traducen (`hardcoded.translate`: `ui`, `ui+unknown`, `all`).
- `XmlLiteralExtractor`: mueve los literales del XML a recursos `itdroid_hc_N` de `strings.xml` y deja la referencia en el atributo.
- `CodeLiteralExtractor`: mueve a recursos `itdroid_code_<hash>` los literales del código que se traducen. El literal se queda en el smali y pasa por `itdroid.hc.Strings.t()`, una clase que se añade a la app (`itdroid-strings.smali`, empaquetada junto a la clase) y que devuelve el recurso de ese texto, o el propio texto si no lo hay.
- `SmaliPatcher`: reescribe los `const-string` del smali con las traducciones, por archivo y línea, solo en las apariciones elegidas; recuerda y restaura los archivos que tocó.
- `HardcodedLog`: escribe `hardcoded_<lang>.csv`, una fila por aparición con lo que se hizo y por qué.
- `PortablePaths`: mantiene lo que se escribe en la carpeta de resultados libre de rutas de la máquina (rutas relativas a la carpeta, hash del APK, y sustitución del inicio de las rutas absolutas en cualquier texto).
- `RunMetrics`: tiempos por fase y uso de la máquina; secciones `environment` y `performance` de `report.json`.
- `ReportServer`: el servidor local del informe web (`--report`).
- `Reanalysis`: recalcula la comparación de evaluaciones guardadas (`--reanalyze`).
- `ExplorationHelper`: lanza `AdbExplorer` por versión de idioma (exploración o replay).
- `LanguageBundle`: lee `settings.properties` (idiomas seleccionados).
- `NotTranslatableStringsDictionary`: strings que no se deben traducir.
- `Helper`: el directorio de trabajo, qué idiomas se escriben de derecha a izquierda (`languageIsRTL`) y la distancia de Levenshtein que usa el emparejamiento de pantallas sin replay.
- `XMLComparator`: decide qué idiomas ya trae traducidos la app y guarda, por idioma, los strings que le faltan.
- `ExplorationException`, `ITDroidException`: excepciones.

Paquete `model/` (grafos y comparación, detección de IPFs):

- `LayoutGraph`: parsea `result.json` a `List<State>` + `List<Transition>`, por id de estado.
- `State`: una pantalla; convierte su `rawXML` en nodos, con el árbol que forman (padre e hijos de cada uno), y en un grafo de relaciones espaciales entre cada dos nodos; conserva el id del estado origen que reproduce.
- `AndroidNode`: un elemento UI (clase, resource-id, bounds, texto, clickable) y su lugar en el árbol.
- `AndroidNodeProperty`: enum de propiedades de nodo.
- `GraphEdgeType`: enum de relaciones espaciales (LEFT/RIGHT/ABOVE/BELOW, alineaciones, CONTAINS/CONTAINED, INTERSECTS), con la relación en que se convierte cada una en un layout espejado. Los conjuntos de relaciones son `EnumSet`: siempre se escriben en el mismo orden.
- `Transition`, `TransitionType`: aristas del grafo (navegaciones).
- `NodePairing`: qué elemento de la pantalla traducida es cada elemento de la pantalla origen. Recorre los dos árboles desde arriba y alinea en orden los hijos de cada par (como un diff), por clase, resource-id, forma y, para desempatar, posición.
- `LayoutGraphComparision`: empareja estados (por id reproducido y parecido suficiente, o posicionalmente cuando no hay replay), compara las relaciones de cada dos elementos emparejados y produce los IPFs con su tipo, más el desglose `ipfSummary` y la cuenta de `ScreenTexts`.
- `ScreenTexts`: cuántos de los textos de las pantallas origen siguen letra por letra en las pantallas traducidas (`screenText`).

Dependencias externas (no van en el repo, ver README): apktool.jar y uber-apk-signer.jar en `extra/`, adb (Android SDK) y un servidor LLM local. Librerías Maven: `json-simple`, `commons-io`, `jdom2`.

# Las fases en detalle

# Fase 0, preflight

Lee los parámetros de entrada (archivo de config, recomendado, o los 7/8 argumentos posicionales). Luego, antes de gastar minutos en traducir o explorar, comprueba dispositivo, APK y LLM. Si alguna de esas tres comprobaciones falla, lo dice de inmediato, termina el análisis estático de la fase 1 (que no las necesita) y solo entonces aborta. Las comprobaciones de la app decodificada y la prueba de arranque van después del análisis estático.

- Dispositivo: `adb devices` con un único device, API 33 o superior (locale por-app sin root), lista de ABIs.
- APK: si trae código nativo (`lib/<abi>/`), alguna ABI debe coincidir con el dispositivo (una app solo ARM no se instala en un emulador x86_64).
- LLM: `LLMServerManager.ensureRunning` garantiza que el endpoint responde o aborta con mensaje claro.
- App decodificada: minSdk no mayor que el del dispositivo, una actividad con MAIN+LAUNCHER (también `activity-alias`) y que no sea una Trusted Web Activity (sus pantallas son del navegador). Una app sin `res/values/strings.xml` se acepta: no hay recursos que traducir, pero sus hardcoded se detectan y se traducen igual. Si `AppFramework` encuentra un framework multiplataforma, queda en `preflight.app.frameworks` y la consola avisa: esa app no se rechaza, pero los textos de su interfaz no están donde ITDroid traduce.
- Prueba de arranque: instala el APK ORIGINAL con sus permisos concedidos, lo lanza, comprueba que queda en primer plano y que no crashea, y lo desinstala. Separa "la app no corre en este dispositivo" de "la reconstrucción de ITDroid la rompió".

Cada rechazo es una `ITDroidException` con prefijo `PREFLIGHT:` y el motivo, queda en `report.json` (`error`) y el proceso termina con código 1.

# Fase 1, análisis estático e inventario

Dónde está el código. El `applicationId` no lo dice: un sabor de compilación le añade un sufijo (`com.x.app.pro` con el código en `com.x.app`) y el namespace de Gradle puede no tener relación con él (`org.birthdayadapter` con el código en `fr.heinisch.birthdayadapter`). `AppCode.packageRoots` reúne tres fuentes: la carpeta del `applicationId` si tiene código, el paquete de una clase `BuildConfig` que declare ese `applicationId`, y las clases del manifiesto (la clase `application` y las actividades lanzables sirven de ancla; la raíz es el paquete común de los componentes que comparten su prefijo). Se descartan los prefijos de librerías y, de dos raíces anidadas, queda la exterior. `hardcoded.codePackages` añade raíces a mano.

Qué hace el código con cada literal. `SmaliScan` lee cada método hacia adelante desde el `const-string`, siguiendo el registro: una copia (`move-object`) es otro nombre del mismo texto, una instrucción que escribe el registro lo pierde, un `goto` se sigue y una rama condicional no. La primera instrucción que consume el texto decide: una llamada se clasifica con `ConsumerRules` (interfaz, log, excepción, datos, compilador); si la regla dice que el texto pasa a un `StringBuilder` se sigue el builder hasta su `toString()` y luego el resultado, y si dice que la llamada devuelve un texto derivado (`String.format`, `trim`) se sigue el resultado. Lo que el escaneo no puede decidir queda dicho como tal: llamada a un método de la app o de una librería que la tabla no conoce, campo, valor de retorno, array, concatenación de destino desconocido, o sin uso.

Qué se lista y qué se traduce. `HardcodedStringFinder.hits` agrupa las apariciones por texto, descarta el ruido (claves de Compose, descriptores, URLs, rutas, patrones de fecha) y los identificadores sueltos salvo que una llamada del framework los muestre. `StringInventory` marca como no traducible, con su razón, el nombre de la app, lo que falla las reglas de texto y lo que tiene forma de dato (SQL, hash, identificador con puntos o guiones bajos, extensión de archivo, expresión regular). Para el resto decide la política (`HardcodedPolicy`), aparición por aparición: con `ui` (por defecto) se traducen las apariciones que van a una llamada que muestra el texto o a una función composable; con `ui+unknown`, además, las que el escaneo no sabe decidir; nunca las que van a logs, excepciones, APIs de datos o son del compilador, salvo con `all`. Un literal es traducible si al menos una de sus apariciones lo es, y solo esas (`patchPlaces`) se parchean: el mismo texto puede ser etiqueta en un método y clave en otro. Un literal que una llamada del framework muestra se traduce aunque parezca una constante (`CANCEL`); una función composable que la tabla no conoce es evidencia más débil, porque también recibe nombres de iconos, así que un identificador suelto (`arrow_back`) no se toma por texto.

Literales del XML. Antes de leer los recursos, `XmlLiteralExtractor` sustituye cada literal por `@string/itdroid_hc_N` y añade ese string a `res/values/strings.xml` (lo crea si la app no tiene), con el escapado que exige un recurso. La app muestra lo mismo en su idioma origen y el texto pasa a traducirse como cualquier recurso. No se extraen el nombre de la app ni lo que no es texto. En el inventario el hallazgo sigue apareciendo como `hardcoded-xml`, con su archivo y línea y con `extractedTo`.

Literales del código. `CodeLiteralExtractor` mueve a recursos las apariciones que la política traduce (`StringInventory.placesToTranslate`). No sustituye el literal: inserta detrás del `const-string` una llamada estática que recibe el texto y devuelve su recurso, en el mismo registro:

```
const-string v1, "Welcome back"
invoke-static/range {v1 .. v1}, Litdroid/hc/Strings;->t(Ljava/lang/String;)Ljava/lang/String;
move-result-object v1
```

Así no hace falta un registro libre ni un `Context` en el método, y sirve igual en métodos estáticos, lambdas e inicializadores de clase. `Strings.t` obtiene el `Application` del proceso, busca el recurso por nombre (`itdroid_code_` más el `hashCode` del texto en hexadecimal, que el extractor calcula igual) y devuelve su valor en el idioma en que corre la app; si el recurso no existe, si la app aún no tiene `Application` o si algo falla, devuelve el texto que recibió. Los ids no se reservan: los asigna aapt2 al construir. La clase va en una carpeta dex propia (`smali_classesN+1`), y las apariciones de un dex que ya no admite una referencia más (se lee de la cabecera de cada `classesN.dex` del APK) no se mueven y se parchean. El valor del recurso se escribe de modo que leerlo devuelva el literal carácter a carácter: barras, comillas y saltos escapados, `formatted="false"`, y entre comillas si tiene espacios de borde. Tras construir el origen se comprueba que la app arranca; si no construye o no arranca, `Extraction.revert()` deja el código y `strings.xml` como estaban y todo vuelve al parcheo.


El nombre de la app (`StringInventory.appName`: el string `app_name` y el que referencie el `android:label` de `application`, o ese label si es un literal) no se traduce, ni como recurso ni como literal, y no cuenta como string que le falte a un idioma.

`StringInventory.collect` recorre `res/values/*.xml` (salvo `donottranslate.xml`) y recibe los literales del escaneo. Por cada entrada guarda tipo, nombre, texto (XML interno, así se conservan `<b>` o `<xliff:g>`), origen y, si no se traduce, la razón: `translatable="false"`, diccionario de no traducibles, referencia (`@string/x`), sin letras, solo números y símbolos, texto no humano (path data, clases, fuentes) o nombre de contenedor de datos (`*_values`, `*_keys`, `*_ids`, `*_urls`...). Los literales hardcodeados pasan las mismas reglas, así que constantes como `org.x.app.STOP_ALARM` nunca llegan al LLM.

El inventario es también la memoria de traducción: un mismo texto se traduce una sola vez, y un `strings_inventory.json` previo en la carpeta de salida siembra la memoria de la siguiente corrida.

El idioma origen es el `defaultLng` de `settings.properties` para todas las apps. `SourceLanguageCheck` lee `res/values/strings.xml` y cuenta, entre los textos de tres palabras o más, cuántos llevan alguna palabra corta de ese idioma (`the`, `and`, `of`...); con ocho frases o más y menos del 30 % de ellas, avisa de que los textos no parecen estar en ese idioma y lo deja en `sourceLanguageCheck`. No cambia el idioma origen ni dice cuál es el de los textos, y solo conoce inglés, español, francés, alemán, portugués e italiano.

# Fase 2, construcción y exploración del origen

Se reconstruye la app intacta (si apktool no puede, la app no se puede procesar y se aborta con ese motivo). `AdbExplorer.explore`:

1. `install` (con permisos concedidos) y `cleanReset` (`pm clear`, sin root; vuelve a fijar el locale por-app, a conceder los permisos runtime y a ejecutar los comandos de `exploration.setup`, que dejan en el dispositivo el contenido que la app necesita).
2. `launch` (monkey), espera a primer plano aceptando el diálogo de permisos si aparece, y ventana de asentamiento: `exploration.settleSeconds` más esperar a que la estructura no cambie durante 1,5 s. Con la primera pantalla asentada se lee el idioma que el sistema guarda para la app (`cmd locale get-app-locales`): si la app lo quitó al arrancar, se repiten los pasos 1 y 2 una vez (`startClean`). Lo hacen las apps que usan AppCompat con `autoStoreLocales`, solo en su primer arranque tras instalarse: copian sobre el idioma del sistema el que guardan ellas, que en una instalación nueva es ninguno, y marcan la copia como hecha en un ajuste que `pm clear` no borra. Un diálogo del sistema de "la app no responde" que tape la pantalla se cierra con "esperar" antes de volcar.
3. DFS in-place: captura el estado con `stableDump`, extrae los clickables (los campos de texto no se tocan), los toca, recursa en estados nuevos y vuelve con el botón atrás o, si no vuelve, con reinicio limpio y repetición del camino. Cada tap, cada atrás y cada reinicio queda grabado como acción. El recorrido termina al llegar a `exploration.maxStates` pantallas, al pasar `exploration.maxMinutes`, o cuando no queda nada por tocar (con `exploration.maxClickables` elementos por pantalla como tope); un 0 en cualquiera de las tres claves quita ese límite.
   Una pantalla se da por asentada cuando dos volcados seguidos tienen la misma firma. Cada volcado de `uiautomator` tarda unos 2,4 s en el emulador, porque arranca un proceso y espera un segundo sin cambios en la interfaz, así que no se repite cuando el explorador sabe qué pantalla debe haber (la que deja un atrás, un paso del camino tras un reinicio): si el primer volcado muestra esa firma, basta. Una pantalla que se ve por primera vez, o un toque que puede navegar o no, pasan siempre por los dos volcados. Tras un atrás, y tras un toque hacia una pantalla conocida, se espera a que cambie la ventana con el foco, con un tope de 0,4 s; el toque que prueba un elemento espera 0,9 s fijos. Si lo que sale de ese toque es la misma pantalla salvo por unos pocos elementos, puede ser un aviso pasajero: se vuelve a mirar dos veces, cada 2,5 s, y solo cuenta como pantalla si la diferencia sigue ahí.
4. Firma de estado: `resource-id` + clase + `content-desc` (dígitos plegados) + si el nodo muestra texto, ignorando el teclado. Además se guarda la estructura (`resource-id` + clase) comparable entre idiomas.
5. Opcional (`exploration.fillForms`): al llegar a una pantalla con campos de texto pide al LLM un valor por campo, lo teclea y lo graba; el replay teclea exactamente lo mismo.
6. Emite `result.json` y `exploration_script.json` (estados con su camino desde la raíz y sus entradas, la lista de acciones y un resumen: cuántas pantallas, qué terminó el recorrido, cuánto tardó y en qué se fue el tiempo), y desinstala la app. `report.json` recoge ese resumen en el idioma origen (`exploration`), junto con las actividades visitadas y las que declara el manifiesto.

# Fase 3, idiomas destino

Antes de cada idioma se comprueba que el dispositivo responde (`requireDevice`); si no, se le espera hasta `DEVICE_WAIT_SECONDS` y, si no vuelve, la corrida aborta ahí con lo evaluado hasta entonces, en vez de anotar el mismo error de instalación en cada idioma que falta.

Por cada idioma (`ITDroid.processLanguage`), con la app fuera del dispositivo:

1. Recursos: si la app no trae ese idioma (`XMLComparator`), `LLMTranslator.translate` agrupa las entradas del inventario, traduce los textos únicos (en lotes de 25 con un modelo de chat general, o uno por petición con un modelo de traducción) y escribe `temp/res/values-<lang>/strings.xml`. Los ítems que son referencias o datos se copian tal cual; los recursos que ya definía el idioma no se tocan. Si la app sí trae el idioma, su traducción se evalúa tal como está y solo se añaden los strings `itdroid_hc_N` extraídos del XML.
2. Hardcoded del código: los movidos a recursos ya quedaron traducidos en el paso 1. Para los que no se pudieron mover, `translateUiLabels` los traduce y `SmaliPatcher.patch` reescribe los `const-string` de las apariciones elegidas (no ASCII como `\uXXXX`), en la línea donde están ahora (`Extraction.currentLine`). Toda traducción conserva el espacio inicial y final del original (un literal suele ser un trozo de una frase que el código concatena). `HardcodedLog` escribe `hardcoded_<lang>.csv`.
3. `APKToolWrapper.buildAPK` reconstruye y firma el APK, que se copia a `<pkg>-<lang>-aligned-debugSigned.apk`; el smali vuelve a quedar intacto. Ese APK solo hace falta para instalarlo y reproducir el recorrido: con `output.keepLanguageApks=false` se borra al terminar el idioma.
4. Respaldo: el explorador anota en `replay_report.json` los crashes de la app durante el replay (y en `exploration_script.json` los del original durante la exploración). Si el APK del idioma no llega a primer plano, o crashea con una excepción que el original no dio, el idioma se reconstruye sin parches y sin las traducciones de los literales del código (`CodeLiteralExtractor.removeTranslations`), se repite el replay y quedan `hardcodedFallback`, `revertedHardcoded` y `crashes` en `report.json`, y las filas como `reverted` en el CSV.
5. `AdbExplorer.replay`: instala, reinicio limpio, lanza, asienta, y ejecuta las acciones grabadas. Antes de cada acción comprueba estar en el estado esperado (por la firma aprendida en esta versión) y, si no, reinicia y rehace el camino. Cada pantalla capturada se guarda con el id del estado origen que reproduce. Las divergencias (elemento no encontrado, pantalla distinta, salida de la app) se anotan en `replay_report.json`, nunca abortan. Al terminar desinstala la app.
6. `LayoutGraphComparision` compara cada estado con el estado origen que reproduce:
   - Empareja el estado por id reproducido si está en la misma actividad y `NodePairing` logra emparejar al menos la proporción `comparison.minSimilarity` (0,5 por defecto) de los elementos de la pantalla mayor. Si no, la app mostró otra cosa en ese paso y el estado queda en `statesNotCompared` con el motivo.
   - Para cada dos elementos emparejados compara las relaciones que tenían en el origen con las que tienen ahora. Un elemento con una relación perdida o ganada es un hallazgo; también lo es un elemento que solo tiene una de las dos pantallas, reportado una vez en lo más alto de lo que falta o sobra.
   - Dos reglas dejan fuera lo que no es un fallo. Un cambio de alineación se ignora si cualquiera de los dos elementos es un texto (un texto traducido cambia de ancho). Y entre un idioma de izquierda a derecha y uno de derecha a izquierda (`Helper.languageIsRTL`) se espera que el layout se espeje: que izquierda y derecha se intercambien no se reporta; se reporta que dos elementos hermanos no se hayan intercambiado (`missingMirroring`).
   - Cada hallazgo lleva sus tipos (posición perdida o ganada, alineación perdida o ganada, solapamiento nuevo o desaparecido, falta de espejado, componente perdido o añadido) y qué le pasó al elemento mismo (`geometry`: igual, movido, redimensionado, espejado). Un elemento está espejado cuando queda a la misma distancia del borde derecho de la pantalla que tenía del izquierdo; el ancho de la pantalla es el de la pantalla más ancha de las dos exploraciones, no el de la que se compara, que puede ser un diálogo. `amIPFs` es el número de hallazgos; `ipfSummary` lo desglosa.
   - `ScreenTexts` cuenta, sobre las pantallas comparadas, cuántos textos distintos del origen (el texto y la descripción de cada elemento que tiene una palabra, sin lo tecleado en los campos) están letra por letra en la pantalla traducida. Con 5 textos o más y el 90 % sin cambiar, `screenText.sameAsSource` es `true` y la consola avisa: el idioma no llegó a esas pantallas, o son sobre todo datos, y no encontrar hallazgos ahí no dice que la traducción quepa.
7. Con `rtl.repair=true`, en un idioma de derecha a izquierda: tras construir el APK del idioma, `RtlRepair.apply` repara la app decodificada, se construye otra vez (`<pkg>-<lang>-rtl-aligned-debugSigned.apk`) y `RtlRepair.revert` deja la app decodificada como estaba. Después de evaluar la app tal cual, el APK reparado se reproduce en `rtlResults/<lang>` y se compara con el mismo origen; el resultado va en `rtlRepair` dentro del idioma, con los mismos campos que el idioma y `applied` (qué cambió la reparación). Si la app reparada no construye o no se puede evaluar, `rtlRepair.error` lo dice y la evaluación del idioma no cambia. La reparación renombra una lista cerrada de atributos (`padding`, `layout_margin`, `layout_alignParent`, `layout_align`, `drawable`, `layout_goneMargin`, `contentInset` con `Left` o `Right`, más `layout_toLeftOf`, `layout_toRightOf`, los lados de ConstraintLayout y los valores `left` y `right` de `gravity`), solo en el elemento que no tiene ya el atributo de inicio o fin; con `minSdk` menor que 17 los añade junto a los de lado en vez de sustituirlos. Las copias forzadas van a `layout-ldrtl` conservando los demás calificadores de la carpeta.

# Fase 4, cierre

Quita los parches del último idioma y construye una vez más la app decodificada tal como quedó: el código sin parches, los recursos de todos los idiomas procesados y los literales movidos a recursos. Esa construcción es el APK internacionalizado, el de nombre por defecto; si falla, no queda archivo con ese nombre y `internationalizedApk` en `report.json` lo dice (`built: false`) y nombra la construcción origen. Con `rtl.repair=true` se construye con la reparación aplicada (`internationalizedApk.rtlRepair`); si así no construye, se construye sin ella y queda `rtlRepairError`. Después reinstala el APK original en el dispositivo y escribe el `report.json` final, con estado `finished`. Si algo abortó, el estado es `aborted`, el código de salida es 1 y `report.json` conserva lo terminado; si el proceso se cierra desde fuera, un gancho de cierre deja el estado en `interrupted`.

# El servidor LLM

ITDroid es agnóstico al motor: solo habla un endpoint HTTP OpenAI-compatible. El `translation.autoStart` (o el 8º argumento posicional) decide cómo se provee:

- Sin espacios (ej. `qwen2.5:3b`): modo modelo Ollama. Arranca `ollama serve` si está caído, hace `ollama pull` y fija endpoint y modelo vía system properties.
- Con espacios (ej. `llama-server -m m.gguf --port 8080`): modo comando. Ejecuta el comando vía shell y espera al endpoint de `.env`.
- Vacío: pre-flight; el server debe estar arriba o aborta con mensaje claro.

El estilo de petición lo fija `translation.style`: `json` (lotes de 25 textos en un objeto JSON, para modelos de chat), `translategemma` (un texto por petición con el prompt fijo de TranslateGemma), `template` (un texto por petición con `translation.promptTemplate`, por defecto el prompt de Hy-MT2) o `plamo` (un texto por petición, con los códigos de idioma en el mensaje de sistema); `auto` lo deduce del nombre del modelo. En los estilos de un texto por petición van cuatro peticiones en paralelo, los placeholders viajan enmascarados como `<phN>` (`translation.maskPlaceholders`) y cada respuesta pasa por la comprobación de placeholders; si los pierde dos veces, el texto se queda en el idioma origen. Un GGUF del Hub servido por Ollama puede traer la plantilla de chat corrupta: comprobar `ollama show <modelo> --template` y, si hace falta, crear con `ollama create` un modelo derivado cuyo Modelfile tenga un `FROM hf.co/<repo>:<cuantización>` y un `TEMPLATE` con la plantilla oficial del repositorio del modelo.

Qué viaja al modelo y qué se acepta de vuelta (`LLMTranslator`), igual en todos los estilos:

- Lo que debe volver intacto se enmascara con tokens `<phN>` y se restaura después: especificadores de formato, placeholders con nombre, etiquetas y entidades (en los estilos de un texto por petición), y siempre los términos protegidos (`protected-terms.txt` de la carpeta de settings, más el nombre de la app cuando es reconocible como nombre), las direcciones de correo y las URL. Un `%` solo es formato si va doblado, lleva índice (`%1$s`) o le sigue directamente una conversión y no viene tras un dígito; en `50% of users` es un porcentaje.
- Las comillas dobles que envuelven un valor entero son sintaxis de recursos: se quitan antes de enviar y se vuelven a poner sin escapar.
- La traducción conserva el espacio inicial y final del original.
- `rejectReason` descarta la respuesta que no es una traducción: vacía, sin un placeholder o un término protegido del original, desproporcionadamente larga, un comentario (`Nota: ...`, `Sure, ...`), o en letras latinas cuando el idioma usa otro alfabeto. El texto se pide otra vez y, si vuelve igual, queda en el idioma origen; los rechazos se cuentan en `performance.llm.answersRejected`.
- PLaMo Translate (`translation.style=plamo`): un texto por petición, con `source_lang` y `target_lang` en el mensaje de sistema y, para textos de hasta tres palabras, una línea de contexto tras `---` que dice que es una etiqueta de la interfaz de una app. Tiene una lista cerrada de 53 idiomas (`unsupportedLanguage`); un idioma fuera de ella no se evalúa, porque el modelo responde en otro sin avisar.

Un server que ITDroid arrancó se apaga al final (shutdown hook, mata el árbol de procesos). Uno que ya estaba corriendo se reutiliza y no se toca. La salida del server va a `<output>/llm-server.log`.

# Informe web

`java -jar ITDroid.jar --report [carpeta] [--port N] [--no-browser]` arranca `ReportServer` (el `HttpServer` del JDK, solo en 127.0.0.1): sirve `webreport/` en `/`, la carpeta de resultados en `/results/` y dos consultas:

- `/api/results`, un índice que recorre las subcarpetas con `report.json` y resume cada evaluación (estado, idiomas, IPFs, fecha, dispositivo, modelo, y, si hay un `meta.json` junto al informe, la telemetría que dejó el harness de evaluación por lotes, que no forma parte del repositorio). De cada idioma dice en qué carpeta está su exploración (`trnsResults` o `noTrnsResults`), mirando el disco. El estado lo calcula el servidor (`effectiveStatus`): un informe que dice `running` se muestra como `interrupted` si el proceso que lo escribía ya no existe (el informe lleva su `pid` y la hora en que arrancó, para no confundirlo con otro proceso que reciba el mismo número) o, en informes antiguos sin `pid`, si lleva más de tres horas sin reescribirse.
- `/api/files/<evaluación>`, los archivos de una evaluación con su ruta y tamaño (las capturas se cuentan por carpeta, no se listan). Solo admite una subcarpeta directa de la carpeta servida.

La página (`webreport/js/main.js`, sin dependencias) lista las apps y muestra la seleccionada en pestañas:

- Resumen: la app, la corrida y una fila por idioma con el desglose de sus hallazgos y la parte del texto origen que sigue en pantalla. Dice con qué está hecha la app y avisa si es un framework multiplataforma, si los textos por defecto no parecen del idioma origen y si la exploración terminó sin nada más que tocar (`exhausted`).
- Idiomas e IPFs: por idioma, el desglose de `ipfSummary` y un botón por tipo de cambio que filtra las pantallas. Un idioma cuyas pantallas muestran el texto del origen lleva la etiqueta "same text as source" y la cuenta. Con la reparación de RTL, el idioma muestra qué cambió la reparación y los hallazgos antes y después, y cada pantalla añade la captura de la app reparada. Cada pantalla se abre con la captura origen y la traducida (lee los `result.json`, empareja la pantalla por `sourceStateId` y dibuja cada elemento donde está en esa pantalla, a partir de los `bounds` del volcado, en el mismo orden de documento con que ITDroid numera los nodos). Cada recuadro se coloca como proporción del tamaño de la pantalla, sobre la imagen sola. Ese tamaño es el de la captura una vez cargada y, hasta entonces, lo más lejos que llega cualquier volcado de la exploración: el volcado de un diálogo solo llega a los bordes del diálogo. En un idioma de derecha a izquierda, una tercera imagen dibuja dónde estaría cada elemento sin espejar si la pantalla estuviera espejada (a la misma distancia del borde derecho que hoy del izquierdo). La última imagen es la diferencia visual entre las dos capturas, calculada en un `canvas`: el origen en gris y en magenta cada píxel distinto (en un idioma de la otra dirección de escritura la captura traducida se voltea antes). "Every screen" lista todas las pantallas de la exploración origen, leídas de los `result.json`: las comparadas sin hallazgos, las alcanzadas y no comparadas (`statesNotCompared`, con su motivo) y las que el replay no alcanzó. La dirección `#<evaluación>/languages/<idioma>/<pantalla>` abre esa pantalla.
- Hardcoded: desde `strings_inventory.json`, en los grupos del informe original (extraídos del código, extraídos del XML, no traducidos con su motivo) más los parcheados por idioma; cada string está en un solo grupo, así que suman el total de `report.json`. Una caja de búsqueda y un filtro por uso para todos los grupos, y páginas de 50 filas. Una tabla dice qué quedó aplicado en la build de cada idioma.
- Strings no internacionalizados: de `nonInternationalized`, por idioma, si la app lo traía y qué strings le faltan, con su texto por defecto y la traducción de ITDroid.
- Rendimiento de ITDroid y entorno: lo que costó la evaluación (tiempo por fase, peticiones al modelo, uso de la máquina), no el rendimiento de la app.
- Artefactos: enlaza solo lo que `/api/files` dice que existe, cada exploración en la carpeta donde está.

# Análisis estático sin dispositivo

`java -jar ITDroid.jar --static <config>` (o `--static <apk> <paquete> <extra> <settings> <salida>`) ejecuta solo la decodificación y la fase 1: escribe `hcs.txt`, `strings_inventory.json` y un `report.json` con `hardcoded`, `inventory`, `codePackages`, `nonInternationalized` y `preflight.app`, marcado con `itdroid.mode = "static"`. No resuelve dispositivo ni arranca el servidor LLM. Es el mismo código que corre en una evaluación completa, que por eso deja esos mismos datos aunque después falle el dispositivo.

# Re-análisis sin dispositivo

`java -jar ITDroid.jar --reanalyze <carpeta> [--out <carpeta>] [--min-similarity <0..1>]` (`helper/Reanalysis`) vuelve a calcular la comparación de evaluaciones ya exploradas: lee el `report.json` de cada una para saber el idioma origen y los idiomas evaluados, carga los `result.json` guardados con `LayoutGraph` y ejecuta `LayoutGraphComparision` tal como lo hace el pipeline. No usa dispositivo ni LLM y no escribe nada en la carpeta de entrada; la salida (por defecto `reanalysis/<nombre de la carpeta>`) lleva un `summary.csv` con los IPFs reportados y los recalculados por evaluación e idioma, el desglose de `ipfSummary` en columnas y la cuenta de textos sin cambiar (`sourceTexts`, `unchangedTexts`, `sameTextAsSource`), que así se puede calcular también sobre evaluaciones anteriores a ella, y por evaluación un `reanalysis.json` y un `ipfs.csv`. Sirve para medir sobre un lote entero el efecto de cualquier cambio en el comparador sin volver a explorar las apps: un lote de 40 apps en tres idiomas se recalcula en un par de segundos, y dos corridas sobre los mismos datos escriben los mismos archivos.

# Pruebas

`mvn test` corre las pruebas unitarias (JUnit 5), que viven en `test/` con los mismos paquetes que `src/`. No necesitan dispositivo ni LLM.

- `model/`: relaciones entre los nodos de una pantalla (`StateRelationsTest`), emparejamiento de los elementos de dos pantallas (`NodePairingTest`), reglas de la comparación sobre pantallas hechas a mano (`ComparisonRulesTest`, y `RtlComparisonTest` para los idiomas de derecha a izquierda), carga de `result.json` (`LayoutGraphTest`) la cuenta de textos que no cambiaron (`ScreenTextsTest`) y la comparación de cuatro evaluaciones reales guardadas en `test/fixtures/evaluations` (`StoredEvaluationsComparisonTest`), cuyos conteos de IPFs y su desglose quedan fijados: un cambio en el comparador que los mueva tiene que ser intencionado.
- `helper/`: localización del código (`AppCodeTest`), escaneo y clasificación de cada literal (`SmaliScanTest`), literales del XML (`XmlLiteralFinderTest`), strings que le faltan a cada idioma (`XMLComparatorTest`), detección de hardcoded e inventario sobre una app escrita a mano (`test/fixtures/decoded/shop`: smali en dos dex, código fuera de la carpeta del `applicationId`, recursos y un layout), extracción de literales a recursos (`XmlLiteralExtractorTest`, `CodeLiteralExtractorTest`), parcheo del smali (`SmaliPatcherTest`), el registro por idioma (`HardcodedLogTest`), la lectura de la configuración (`RunConfigTest`), los ajustes de construcción y las copias de APK (`APKToolWrapperTest`), el modo de re-análisis (`ReanalysisTest`), las rutas portables (`PortablePathsTest`), el servidor del informe (`ReportServerTest`: estado de las corridas, carpeta de cada idioma y listado de archivos), el cierre del informe de una corrida detenida (`ITDroidReportTest`), la detección del framework (`AppFrameworkTest`), el idioma de los textos por defecto (`SourceLanguageCheckTest`), el archivo de preparación (`SetupScriptTest`) y la reparación de RTL sobre layouts escritos a mano, incluido deshacerla (`RtlRepairTest`).
- `explorer/`: lo que el explorador decide sin dispositivo (`AdbExplorerTest`): si una pantalla que difiere poco de la anterior puede ser un aviso pasajero, y si el sistema conserva el idioma pedido para la app.
- `translator/`: el traductor contra `FakeLlmServer`, un servidor local que imita el endpoint OpenAI-compatible, para probar lotes, reintentos, enmascarado de placeholders, términos protegidos y cifras, y la escritura de `values-<lang>/strings.xml`.

La página del informe tiene su propia comprobación, fuera de Maven porque necesita Node (18 o posterior): `node webreport/test/smoke.js` arranca el servidor del informe sobre `webreport/test/sample`, ejecuta `webreport/js/main.js` con un sustituto mínimo del DOM y comprueba lo que muestra cada pestaña (los hallazgos por pantalla y por tipo, los grupos de hardcoded, la búsqueda, los enlaces de artefactos, el estado de las corridas). La carpeta de ejemplo es una app inventada cuyas pantallas están escritas a mano y cuyos hallazgos calculó ITDroid con `--reanalyze`; si cambia el comparador, se vuelven a calcular igual. Con `--url` comprueba lo que sirva un servidor ya arrancado.

Las pruebas llamadas `knownGap_...` fijan un comportamiento que la auditoría de noviembre de 2026 encontró incorrecto: afirman lo que el código hace hoy y nombran la corrección pendiente; cuando se corrige, la aserción se invierte en el mismo cambio. Hoy no queda ninguna: las de detección, traducción y comparación se invirtieron con sus correcciones.

# Artefactos que se generan

- `temp/`: APK decodificado (`smali*/`, `res/values*/`, `AndroidManifest.xml`); se regenera en cada run.
- `<output>/report.json`: preflight, inventario (conteos), y por idioma estados, emparejamiento, replay e IPFs; además `itdroid` (versión, inicio, fin, estado, y el `pid` del proceso que lo escribe), `environment` (host, dispositivo, motor de traducción, configuración) y `performance` (segundos por fase, peticiones al LLM, CPU y heap de ITDroid, carga de CPU y memoria del host muestreadas cada 2 s por `RunMetrics`). Se escribe desde el primer segundo de la corrida, con estado `running`, y al terminar queda en `finished` o `aborted`; si la JVM se cierra antes (Ctrl+C), un gancho de cierre lo deja en `interrupted`. No lleva nombre de usuario ni rutas de la máquina: `PortablePaths` nombra los archivos de la evaluación por su ruta desde la carpeta de resultados (`apkPath`, y `screenShot` en cada `result.json`), identifica el APK por su `apkSha256` y, en cualquier otro texto (el mensaje de un error), sustituye el inicio de las rutas absolutas por `<output>`, `<itdroid>` o `~`.
- `<output>` es `results/<paquete>` salvo que la config fije `output=`; `results/` es la carpeta que lista el informe web.
- `<output>/strings_inventory.json`: inventario con orígenes, razones de exclusión y traducciones.
- `<output>/<pkg>-aligned-debugSigned.apk`: APK internacionalizado (recursos de todos los idiomas, incluidos los hardcoded de código y de XML movidos a recursos). Todas las construcciones de una corrida se firman con este nombre y se copian al suyo; antes de firmar se borra la anterior, así que el archivo solo existe si la última construcción se firmó. Si el internacionalizado no se pudo construir, no queda archivo con este nombre y `report.json` remite a la construcción origen.
- `<output>/<pkg>-<lang>-aligned-debugSigned.apk`: APK por idioma (recursos y hardcoded traducidos); el del idioma origen es la construcción que se exploró. Con `output.keepLanguageApks=false`, el de cada idioma destino se borra al terminar su evaluación. El APK sin firmar que escribe apktool se borra en cuanto existe el firmado.
- `<output>/{trnsResults,noTrnsResults}/<lang>/result.json`: grafo explorado o reproducido.
- `<output>/rtlResults/<lang>/` y `<output>/<pkg>-<lang>-rtl-aligned-debugSigned.apk`: con `rtl.repair=true`, el grafo reproducido, las capturas y el APK de la app reparada en cada idioma de derecha a izquierda.
- `<output>/trnsResults/<origen>/exploration_script.json`: el recorrido grabado.
- `<output>/.../<lang>/replay_report.json`: acciones reproducidas, estados capturados y divergencias.
- `<output>/.../<lang>/screenshots/`: capturas. Con `debug.graphDumps=true`, además `graph.txt` y `graph.json`: volcados legibles de cada estado con todas las relaciones entre pares de nodos (varios MB por idioma; solo para depurar la comparación a mano).
- `<output>/ipfs.csv`: IPFs en CSV (`language;state;nodePos;ipfScore;langNodePos;kind`).
- `<output>/hardcoded_<lang>.csv`: una fila por aparición de cada hardcoded: parcheado, extraído, revertido o no traducido y por qué.
- `<output>/hcs.txt`: textos hardcoded detectados, por clase y método, cada uno con su uso; después los del XML, por archivo y elemento.
- `<output>/llm-server.log`: salida del servidor LLM (si ITDroid lo arrancó).

# Valores por defecto y dónde se cambian

Cada comportamiento que se puede querer distinto es una línea del archivo de config. `RunConfig` tiene un método por clave, con su valor por defecto, y `itdroid.config.example.properties` las trae todas escritas con ese valor; el README explica cada una.

| Clave | Por defecto | Qué decide |
|---|---|---|
| `hardcoded.translate` | `ui` | qué usos de un literal del código se traducen (`ui`, `ui+unknown`, `all`) |
| `hardcoded.extractCode` | `true` | mover a recursos los literales del código que se traducen, o solo parchearlos por idioma |
| `hardcoded.codePackages` | vacío | raíces de paquete añadidas a mano al código propio de la app |
| `translation.model` | `qwen2.5:3b` | el modelo; la validación final usó `hf.co/pfnet/plamo-3-translate-0.8b-GGUF:Q4_K_M` |
| `translation.style` | `auto` | cómo se le pide la traducción al modelo (`json`, `translategemma`, `template`, `plamo`) |
| `translation.promptTemplate` | prompt de Hy-MT2 | el prompt del estilo `template` |
| `translation.maskPlaceholders` | `true` en los estilos de un texto por petición | enmascarar los placeholders como `<phN>` |
| `translation.context` | una frase que dice que el texto es una etiqueta de una app | el contexto que recibe PLaMo con los textos cortos; `none` lo quita |
| `translation.reuseInventory` | `true` | reutilizar las traducciones de un inventario anterior |
| `translation.timeoutSeconds`, `translation.temperature` | 180, 0 | tope por petición y temperatura |
| `alpha` | 0 | strings sin traducir que se toleran antes de dar un idioma por no traducido |
| `preflight.launchTest` | `true` | probar que la app original arranca antes de procesarla |
| `exploration.maxStates`, `exploration.maxClickables` | 5, 8 | pantallas que se capturan y toques por pantalla; 0 es sin límite |
| `exploration.maxMinutes` | 0 | minutos que puede durar la exploración del idioma origen; 0 es sin límite |
| `exploration.settleSeconds` | 3 | espera tras cada arranque |
| `exploration.fillForms` | `false` | pedir al LLM valores para los campos de texto |
| `exploration.setup` | vacío | archivo con comandos `adb` que se ejecutan tras cada arranque limpio, para dar contenido a la app |
| `rtl.repair` | `false` | evaluar además, en cada idioma de derecha a izquierda, la app con sus layouts reparados |
| `comparison.minSimilarity` | 0,5 | proporción de elementos que hay que emparejar para comparar dos pantallas |
| `output.keepLanguageApks` | `true` | conservar el APK de cada idioma destino |
| `debug.graphDumps` | `false` | escribir `graph.txt` y `graph.json` |

Cuatro archivos de la carpeta de settings se editan sin recompilar: `settings.properties` (los idiomas destino, uno por línea), `strings.xml` (recursos que no se traducen), `protected-terms.txt` (nombres que no se traducen) y `hardcoded-consumers.txt` (qué hace cada API con un string; sus reglas se prueban antes que las empaquetadas).

Lo que no es configuración son constantes del código, cada una en una línea:

- `LLMTranslator`: `TRANSLATOR_PACKAGE_SIZE` (25 textos por lote), `BATCH_CHAR_BUDGET` (2500 caracteres por lote) y `NATIVE_PARALLELISM` (4 peticiones a la vez en los estilos de un texto por petición).
- `APKToolWrapper.MAX_BUILD_ATTEMPTS` (6 intentos de construcción con ajustes).
- `AdbExplorer.FOREGROUND_WAIT_SECONDS` (15 s para que la app llegue a primer plano), `STABLE_INTERVAL_MS` (0,6 s entre los dos volcados que deben coincidir), `TAP_SETTLE_MS` (0,9 s tras un toque de resultado desconocido), `ACTION_WAIT_MS` (0,4 s como máximo esperando una ventana nueva tras un atrás o un toque hacia una pantalla conocida), y `MESSAGE_NODES`, `MESSAGE_POLL_MS` y `MESSAGE_POLLS` (5 elementos, 2,5 s y 2 miradas para decidir si una diferencia pequeña es un aviso pasajero).
- `LLMServerManager.STARTUP_TIMEOUT_SECONDS` (180 s para que responda un servidor que ITDroid arrancó).
- `ScreenTexts.SAME_TEXT_SHARE` (0,9: desde esa parte de los textos origen sin cambiar se avisa de que el idioma muestra el texto del origen) y `MIN_TEXTS` (5 textos como mínimo para juzgarlo).
- `SourceLanguageCheck.EXPECTED_SHARE` (0,3: por debajo de esa parte de frases con una palabra corta del idioma, los textos no parecen estar en él), `MIN_SENTENCES` (8) y `MIN_WORDS` (3 palabras para contar como frase).
- `ITDroid.DEVICE_WAIT_SECONDS` (120 s esperando a un dispositivo que dejó de responder entre dos idiomas).
- `ReportServer.STALE_HOURS` (3 horas sin reescribirse para dar por interrumpido un informe antiguo sin `pid`) y `MAX_LISTED_FILES` (500 archivos por evaluación en la pestaña de artefactos).
- `webreport/js/main.js`: 50 filas por página en las tablas (`pagedTable`) y `DIFF_THRESHOLD` (32, cuánto debe diferir un píxel para pintarse en la diferencia visual).

# Decisiones de diseño clave

- Preflight antes que nada: lo que no se puede procesar se rechaza en segundos con un motivo clasificable (fuera de alcance, entorno, límite de apktool) en vez de fallar a mitad de una exploración.
- Inventario único con origen: una sola fuente para traducir, revisar a mano, deduplicar y reutilizar; permite rastrear cada texto hasta su archivo y línea.
- Grabar y reproducir: las versiones traducidas no exploran por su cuenta, repiten el recorrido grabado, así los grafos se emparejan por construcción y el no determinismo del explorador deja de contaminar la comparación. Cada versión traducida se reconstruye, instala y evalúa de verdad.
- Comparar elementos, no posiciones del volcado: cada elemento de la pantalla traducida se compara con el que es en la pantalla origen, aunque haya elementos de más o de menos, y lo que solo tiene una pantalla es un hallazgo en sí. Entre idiomas de distinta dirección de escritura el espejado es lo esperado y se reporta su ausencia.
- La app fuera del dispositivo mientras se traduce: tras cada exploración se desinstala (procesos y datos incluidos); solo vive en el dispositivo el tiempo de su exploración.
- Strategy en la traducción (`TranslationInterface`): cambiar de IBM Watson a un LLM local fue cambiar la estrategia, sin tocar el orquestador.
- LLM agnóstico al motor: HTTP OpenAI-compatible, sirve Ollama, llama.cpp o LM Studio sin cambiar código.
- Exploración sin root: locale por-app (`cmd locale set-app-locales`) y `pm clear` no requieren root, funciona en emuladores Play Store y dispositivos físicos.
- Original intacto: el APK original nunca se parchea, y el dispositivo termina con él reinstalado.

Para el historial de cambios ver REFACTOR.md. Para cómo ejecutar ver README.md.

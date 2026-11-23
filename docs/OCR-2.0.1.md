# PRIME VOICE CAMERA 2.0.1 – oprava OCR

## Konkrétní příčina

Porovnány tagy `prime-voice-clean-v1.0.0` a `prime-voice-camera-v2.0.0`, soubor `CameraCaptureManager.kt`. Ve 1.0 neexistoval společný inferenční semafor. Ve 2.0 byl `gate.tryCamera()` před kontrolami `running`, `canAnalyze`, intervalu a `busy`. Následný časový/kalibrační skip zavřel ImageProxy, ale nevrátil permit. Další OCR ani ML Kit překlad už nemohl začít; Preview je samostatný CameraX use case, proto dál zobrazoval obraz. Navíc neúspěšné získání permitu nastavovalo `busy=false` bez vlastnictví aktivní OCR práce.

Instrumentovaný test navíc odhalil vazbu kamery na recyklovanou položku `LazyColumn`: na malé obrazovce se PreviewView mimo viditelnou část nemusel vytvořit; po odrolování se jeho DisposableEffect zrušil a zastavil kameru. Jednorázové `view.post` také negarantovalo připojení a rozměření view. Stránka s omezeným počtem ovládacích prvků nyní používá obyčejný posuvný Column, náhled zůstává připojený i mimo viditelnou část a start čeká na attach i layout. Obnoven kompatibilní TextureView režim z 1.0.

Nešlo o chybějící Latin model: `com.google.mlkit:text-recognition:16.0.1` jej bundluje v APK. Předchozí 2.0 test s Bitmap jej úspěšně spustil, ale neprošel skutečnou funkcí analyzátoru, a proto regresi nezachytil.

## Změny

- `FrameAdmission`: interval, pauza a aktivní OCR se kontrolují před získáním permitu. Pouze přijatý snímek dostává idempotentní lease. Dokončení coroutine zavře ImageProxy a uvolní lease i při chybě či zrušení před začátkem těla. Odmítnutý snímek nemění vlastníka aktivní práce.
- CameraX explicitně předává YUV_420_888, KEEP_ONLY_LATEST, jediný analyzer executor a jeden OCR worker. Sdílený permit dál brání souběhu náročného OCR/překladu. ML Kit úloha dokončí práci před uvolněním obrazu.
- Výřez z jasové roviny respektuje cropRect, rotace 0/90/180/270, rowStride, pixelStride i pozici ByteBufferu. Do ML Kit vstupuje už otočená Bitmap s rotací 0; neotáčí se podruhé.
- OCR nezávisí na staženém překladači ani hlasu. Před jejich přípravou zobrazuje text bez vytváření fronty, po přípravě může začít běžné zpracování. Diagnostický režim zobrazuje celý obraz bez výřezu, změnového filtru, úpravy kontrastu, ASR, stability a duplicitních filtrů; neposílá průběžná slova do hlasu.
- Diagnostika ukazuje přijaté snímky, skutečné pokusy OCR, délku a surový výsledek, formát/rotaci, důvod přeskočení a konkrétní chybu. Dále samostatné stavy překladače a TTS. Chyby OCR jsou také v logcat `PrimeVoiceOCR`.
- Kontext posledních osmi dialogů se aktualizuje po úspěšném překladu; AI tak vidí předchozí překlad i během jeho čtení. Gemma dostává poslední dvě repliky a instrukci k přirozené češtině. ML Kit konverzační kontext neumí; kvalita slangu a návaznosti není zaručena.
- Zachovány LIGHT/STANDARD/ULTRA, SAF, validace modelu a bezpečný fallback, ASR, nastavení kamery, celistvé bloky, fronta 40, 0–10s počáteční rezerva, QUEUE_ADD a Bluetooth.

## Ověřovací sada

`./gradlew :core:test :app:lintRelease :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest`

Pět nových JVM regresí pokrývá rychlé vynechané snímky následované překladem, pauzu/teplotu, vlastnictví aktivního OCR, kolizi s překladem a restart i uvolnění po chybě. Předchozí testy fronty obsahují první hrající větu + tři nové titulky, opačné pořadí dokončení překladů, opakování, zpoždění, chyby, kapacitu a ASR nesoulad.

GitHub Actions připraví ML Kit EN→CS na Android 10/API29 x86_64 emulátoru s 4 GB RAM. Nainstaluje skutečný samostatný sherpa-onnx Jirka-low TTS engine 2.0.2 z oficiálně odkazovaného seznamu, zkontroluje SHA-256 a pak vypne Wi-Fi i mobilní data. End-to-end test kontroluje absenci aktivní sítě. Externí hlas není zahrnut do PRIME VOICE APK.

Android testy používají skutečný ML Kit, žádný mock OCR/překladače/hlasu:

- „The weather is beautiful today.“ přes YUV ImageProxy → skutečný výřez → ML Kit; čtyři rotace, obě šířky OCR (960 a 1100), nerovné rowStride/pixelStride a nenulová pozice bufferu.
- Tentýž produkční analyzátor: pozastavení, zámek držený překladačem, rychlé snímky, nesprávný formát a zotavení; každé ImageProxy uzavřeno právě jednou, překladový permit znovu dostupný.
- Živý CameraX v Activity: více příchozích snímků a OCR úloh i po odrolování náhledu, zastavení příjmu při Stop. Emulovaná kamera sama o sobě nesnímá skutečnou televizi.
- Celý offline řetězec: vytvořený obraz dvou anglických replik → skutečné OCR → detektor kompletního bloku → fronta → ML Kit EN→CS → skutečný Android český TTS, neprázdné PCM a `onDone` obou vět. Kontrola pořadí, českých slov, jména a přepnutí režimů s LIGHT fallbackem.
- Původní dvouřádkové OCR, start Activity, native engine probe a zotavení samostatného procesu AI.

Výsledky konkrétního vydání jsou v přiložených `emulator-results.txt`, `model-preparation.txt`, `emulator-logcat.txt` a archivu testů. Workflow publikaci blokuje při chybě testů. Úspěšné sestavení samotné není dokladem funkčnosti OCR.

## Co stále vyžaduje telefon

Fyzický POCO F7 Ultra/Android16, televizní titulky, autofokus, expozice, odlesky, Bluetooth a několikahodinový provoz nejsou nahrazeny emulátorem. Ověřte rostoucí počítadla a surový text v diagnostice, poté vypněte test celého obrazu, nastavte výřez a poslouchejte český překlad. Vyzkoušejte i Lite/LIGHT a režim letadlo po instalaci dat.

Skutečný běh Gemma 3n na fyzickém Android zařízení zůstává neověřený. Zachována je kontrola přesného podporovaného ne-Web souboru a povinný runtime test. Tato oprava nepotvrzuje kompatibilitu uživatelova souboru `gemma-3n-E2B-it-int4-Web.litertlm` a nic takového nesimuluje.

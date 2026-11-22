# Architektura

`app` obsahuje Android adaptéry a české Compose UI. `core` je čistý Kotlin/JVM bez Android závislostí, testovatelný virtuálním časem. Nový projekt nezávisí na žádném předchozím kódu.

```text
CameraX → OCR luminančního výřezu → Channel(CONFLATED)
                                ↓
lokální ASR → časová evidence → MultimodalFusionEngine
                                ↓
                 SubtitleStabilityDetector + assembler
                                ↓
              omezená fronta → překladové workery
                                ↓ (monotónní ID zachovává pořadí)
                  jediný přehrávací worker
                                ↓
         Android TTS QUEUE_ADD → onDone → další věta
```

## Vlastnictví a vlákna

- ViewModel drží jednu relaci a StateFlow. Compose pouze zobrazuje stav a posílá akce. Activity.onStop pozastaví vstupy i začínání nových promluv. Nastavení má samostatný repository.
- CameraX analyzer je na jednom executor vlákně, grayscale výřez/OCR na Default dispatcheru. AtomicBoolean dovoluje jedinou analýzu najednou. ImageProxy se zavře v každé větvi, také při chybě; bitmapa žije až do dokončení ML Kit úlohy. Zastaralé výsledky po zastavení odfiltruje generace kamery.
- Sensor cropRect a sdílený CameraX ViewPort sjednocují PreviewView.FILL_CENTER s obrazem pro OCR. Region je normalizovaný k viditelnému náhledu. Převod souřadnic podporuje 0/90/180/270 stupňů.
- OCR snapshots se smějí slučovat v conflated kanálu: jde o nové pozorování stejného obrazu. **Dokončené bloky se tímto kanálem neposílají.** Po stabilizaci se uchovají do přijetí do fronty. Při tlaku se analýza zastaví.
- Queue manager chrání stav Mutexem. Aplikace používá jeden překladový worker; sdílený InferenceGate serializuje také OCR. Android ML Kit adapter serializuje přístup ke klientovi; překlad dál probíhá nezávisle na TTS.
- Jediný playback job vybírá výhradně první položku, čeká na její READY a nakonec onDone. FAILED blokuje pozdější položky. Změna hlasu, ukázka i přehrávání používají stejný TTS Mutex.

## Stavový automat fronty

`TRANSLATING → READY → SPEAKING → dokončeno`

`TRANSLATING/SPEAKING → FAILED → ruční opakování nebo přeskočení`

Nové položky se přidávají za již existující. Příchod položky nikdy nezastaví TTS. Timeout překladače je 30 s (lokální AI má vlastní limit 12 s) na pokus, dva pokusy s krátkým odstupem. Chybující překladač proto nepředstírá úspěch ani nezahazuje text. TTS watchdog 180 s označí chybu a zastaví pouze vadnou/visející TTS úlohu před případným opakováním; nejde o reakci na příchod nového titulku.

Kapacita fronty 40, dokončené bloky odmítnuté kvůli kapacitě se krátce drží ve vstupním koordinátoru. Po zaplnění se nové OCR nepřijímá. Uživatel vidí počet čekajících vět, stáří čela i poslední naměřenou latenci. Vymazání/přeskočení je výslovné a nikdy neodstraní SPEAKING položku.

## Počáteční rezerva

Při explicitním spuštění se nastaví rezerva 0–10 s. Čas se počítá od prvního READY titulku na čele. Po jejím vyčerpání zůstává relace otevřená: další položky se přehrávají ihned, i když fronta mezitím krátce osiří. Nastavení se za běhu nemění. Zastavení a nové spuštění zahajuje novou rezervu.

## OCR a fúze

Stabilní interpunkčně ukončený blok čeká adaptivně přibližně 650–1300 ms a nejméně tři snímky. Bez koncové interpunkce čeká déle (1500–2600 ms), s omezeným dodatečným oknem 1000 ms pro pokračování. Vynucené dokončení starého kandidáta vyžaduje alespoň dva snímky a stabilní úsek. Jediný záblesk se nepovažuje za dialog.

ASR používá angličtinu a pouze on-device engine. K dispozici je nejvýše osm koncových výsledků; porovnávají se jen v časovém okně u OCR. Úpravy vyžadují vysokou ASR jistotu a lexikální shodu, opravují jen nejistý obraz. Neznámé confidence (-1) se nepovažuje za důvěryhodný údaj. Skóre OCR vychází z kontrastu, ostrosti a poškozených znaků; je to heuristický odhad, nikoli kalibrovaná pravděpodobnost.

## Audio a systém

Mediální AudioAttributes respektují Android routing včetně Bluetooth. Nezapíná se SCO. Audio focus a události odebrání audio zařízení pozastaví začínání dalších vět. Systém může právě hrající větu přesměrovat do telefonu; zachování celé promluvy má podle zadání přednost před jejím automatickým utnutím.

Tepelný stav pochází z PowerManager. Při MODERATE se omezí OCR, při SEVERE ještě více a vypne se pomocný odposlech, při CRITICAL se relace pozastaví. Nejde o garanci konkrétní teploty ani výdrže.

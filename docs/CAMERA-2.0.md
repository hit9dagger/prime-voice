# Architektura a ověření CAMERA 2.0

## Rozhodování o režimu

ExecutionPolicy je čistá Kotlin logika s automatickými testy. ActivityManager poskytuje fyzickou a dostupnou RAM, příznaky lowRamDevice a lowMemory; StatFs skutečně volné interní úložiště. Nezaměňujeme dostupnou a celkovou RAM ani velikost modelu s potřebnou pracovní pamětí.

Telefon s méně než 5 GiB skutečné RAM nebo lowRamDevice používá LIGHT i po ruční volbě ULTRA. Pravidlo pokrývá telefony prodávané jako 4 GB, které systému hlásí méně. Náročnější režim se nespustí jen proto, že byl vybrán. Pro ULTRA vyžadujeme nejméně 7 GiB celkové a 4 GiB dostupné RAM při načtení, 768 MiB rezervy na disku a úspěšný test enginu. Jde o konzervativní vstupní podmínky, nikoli záruku výkonu. Při systémovém lowMemory nebo méně než 512 MiB volné RAM se AI uvolní. Callbacky Androidu doplňuje kontrola každých 5 s, protože novější Android některá paměťová upozornění neposkytuje.

LIGHT: 1280×720 CameraX, výřez do 960 px, 400 ms mezi analýzami, 1200 ms při neměnném obrazu. Při zahřívání se interval dále prodlužuje. FULL HD se používá pouze při skutečně aktivní ověřené AI. ImageAnalysis KEEP_ONLY_LATEST, žádný zásobník snímků, jeden pixelový pracovní buffer místo dvou. CameraX může při nedostupnosti požadovaného rozlišení zvolit nejbližší podporované. Preview používá výkonnější SurfaceView, pokud jej CameraX podporuje. Audio TTS zůstává systémové, offline a sériové bez přerušování.

## Souběh a zotavení

InferenceGate sdílí jeden permit pro OCR a překlad. Kamera jej získává neblokujícím způsobem, jinak snímek okamžitě zavře. Ve frontě je jeden překladový worker a jeden hlasový worker; překlad může běžet během právě přehrávaného TTS. Pomocné ASR se pozastaví během náročné AI a českého TTS. Ostatní uživatelské funkce zůstávají: celé bloky, stabilita OCR, výřez, jména, 8 dialogů kontextu, deduplikace, Bluetooth, 0–10 s počáteční rezerva, 40 položek a explicitní přeskočení.

LiteRT-LM běží v privátní službě `:inference`, nikoli v procesu kamery/UI. Služba není exportovaná, je pouze vázaná během používání a není skrytou foreground service. Má jediný pracovní executor. Messenger přenáší malé textové zprávy; nepřenáší bitmapy, video ani audio. Načtení má limit 90 s a jednotlivá inference 12 s. Při timeoutu klient ukončí svůj vlastní proces AI, odpojí vazbu a RecoveringTranslator použije ML Kit pro stejný titulkový blok. Nejde o spouštění dalšího překladače vedle stále běžící nativní inference. Smrt služby se projeví jako chyba pending požadavku. Zrušení uživatelem zůstává zrušením, nikoli novou položkou fronty.

Proces aplikace může Android při kritickém nedostatku prostředků přesto ukončit; absolutní přežití systémového OOM nelze garantovat. Fronta se jako dříve uchovává v RAM, ne na disku. Modely se ukládají do noBackupFilesDir, nejsou v APK, nezálohují se. Import a download streamují 128KiB bloky, kontrolují přesnou délku a SHA-256 a až pak atomicky přejmenují soubor. Neúplné části se odstraní. Příprava je vědomě pouze v popředí a po zrušení začíná znovu.

## Modely a skutečná zjištění

**STANDARD:** kandidát [Qwen3 0.6B od LiteRT Community](https://huggingface.co/litert-community/Qwen3-0.6B), revize `8d42ab5f1db14cad987fac57aeb17d070f9e180d`, soubor `Qwen3-0.6B.litertlm`, 613722264 B, SHA-256 `c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108`. Skutečně stažen a spuštěn na Linux x86_64 přes LiteRT-LM JVM 0.16.1, CPU, dvě vlákna, 2048 tokenů, deterministické vzorkování. Čtyři repliky včetně „Do not open the door.“ a „Jason, where are you?“ nevrátily češtinu ani s jednodušší instrukcí. Tím NEprošel přijímacím testem; proto je jeho aktivace a download v aplikaci zablokovaný a STANDARD používá ML Kit. Výsledek není obecné tvrzení o všech variantách Qwen ani jiných enginech. Logy jsou v `model-validation/`.

**ULTRA:** [oficiální Gemma 3n E2B](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm), revize `c03b6f60b8da6c5400b6838a2cf26420f80c0a01`, soubor `gemma-3n-E2B-it-int4.litertlm`, 3655827456 B, SHA-256 `2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6`. Podpora modelové rodiny a formátu je uvedená v [oficiální dokumentaci LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md) a [oficiálním katalogu Gallery](https://github.com/google-ai-edge/gallery/tree/main/model_allowlists). Knihovna 0.16.1 je skutečně připojená, ne reflexní stub. V aplikaci používáme CPU backend; NPU ani kompatibilitu s konkrétním GPU neslibujeme. Nativní knihovny mají PT_LOAD zarovnání 16 KiB.

Gemma soubor je u vydavatele omezený přijetím jeho podmínek. Neobcházíme tento krok, nepoužíváme neoficiální zrcadla ani přibalené tokeny. Uživatel stáhne soubor přes oficiální stránku a importuje ho. V tomto prostředí nebyl Gemma model spuštěn; ULTRA je podmíněná integrace, nikoli prohlášení o otestovaném výkonu na POCO. Každá aktivace v telefonu provádí zkoušku skutečného načtení a překlad záporného rozkazu a otázky se jménem. Test má odhalit hrubou nekompatibilitu, nikoli zaručit kvalitu slangu nebo každé věty. Při neúspěchu je aktivní LIGHT.

## Kompatibilita a distribuce

Android 10+ (API 29), cílové API 36. Na Androidu 10–11 je pomocné offline ASR nedostupné; kamera, překlad a TTS fungují nezávisle. Univerzální APK obsahuje arm64-v8a, armeabi-v7a a x86_64. 32bitové ARM používá LIGHT; LiteRT-LM je přibalen jen pro dostupné 64bitové ABI. Dostupnost nativní knihovny se na způsobilém telefonu ověřuje skutečným načtením v privátním procesu. Žádné velké modelové soubory se do APK nebalí; malé původní OCR součásti ML Kit jsou přibalené pro offline rozpoznávání.

Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, JDK 21. AGP 8.13.2 zahrnuje R8 podporující metadata Kotlin 2.3 používaná LiteRT-LM 0.16.1; původní AGP 8.13.1 při minifikaci varoval, proto byl aktualizován. Release sestavení je minifikované, s kompresí nativních knihoven (Android je při instalaci rozbalí), podepsané vývojovým klíčem. Součástí vydání je menší ARM64 APK pro POCO a univerzální APK. Při změně podpisového klíče může být nutná odinstalace starší testovací verze.

Fyzický 4GB telefon, Bluetooth a několikahodinová relace nebyly k dispozici. Automatické testy pravidel paměti a pořadí fronty je nenahrazují. Výsledky sestavení a případných emulátorových testů jsou v GitHub Actions a poznámkách vydání.

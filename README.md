# PRIME VOICE

Nový samostatný nativní Android projekt. Kamerou čte celé bloky anglických titulků z televize, lokálně je překládá do češtiny a přehrává přes systémový Android Text-to-Speech. Žádné propojení s PS5, účet, API klíč ani předplatné.

**Cílový systém:** Android 16 / API 36; minimum Android 12 / API 31. Kotlin, Compose, CameraX, ML Kit, Coroutines a StateFlow. Neobsahuje žádný webový obal ani simulovaný překlad.

## První spuštění

1. Nainstalujte `PRIME-VOICE-1.0.0-debug.apk`. Jde o podepsané vývojové APK pro přímou instalaci, nikoli produkční distribuci v Google Play.
2. V aplikaci stáhněte překladový model. Přepínač Wi-Fi je ve výchozím stavu zapnutý; přibližně 30 MB na jazykový model, skutečný objem může poskytovatel změnit. Pro první stažení je potřeba internet.
3. V systému nainstalujte český **offline** TTS hlas. Aplikace automaticky projde nainstalované TTS enginy a nabídne jejich skutečné dostupné české offline hlasy. Po instalaci dalšího hlasu zvolte „Obnovit nainstalované hlasy“.
4. Na televizi zapněte anglické titulky. Telefon stabilně umístěte před televizor a stiskněte **SPUSTIT PŘEKLAD**. Kamera si vyžádá oprávnění.
5. Pomocí **Vybrat oblast** tažením označte všechny řádky titulků a stiskněte **Hotovo**. Výřez se uloží. Během výběru probíhá náhled, OCR je pozastavené. Klepnutí do náhledu mimo výběr zaostří.
6. Hlas, jeho ukázku a počáteční zpoždění nastavujte při zastaveném překladu. Rychlost a výška se použijí od další promluvy. Zpoždění 0–10 s je jednorázová rezerva od připravení prvního překladu, nikoliv opakované čekání po každé větě.
7. Bluetooth sluchátka/reproduktor připojte standardně v Androidu a zvolte mediální výstup. Hra zůstává slyšet z televize.

## Jirka a další hlasy

Jirka není vymyšlený název: existuje český Piper model `cs_CZ-jirka-medium`. Jeho modelová karta uvádí dataset CC0. Projekt sherpa-onnx zveřejňuje skutečné Android **TTS engine** APK, také pro tento model.

- [Oficiální seznam engine APK](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html): pro POCO F7 Ultra vyberte `arm64-v8a` + `cs_CZ-jirka-medium`, nikoli samostatnou ukázkovou TTS aplikaci.
- [Karta modelu Jirka](https://huggingface.co/rhasspy/piper-voices/blob/main/cs/cs_CZ/jirka/medium/MODEL_CARD).
- [Zdrojový kód TTS enginu](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTtsEngine).

Instalace externího enginu je volitelná a ruční. PRIME VOICE jej nestahuje ani neinstaluje skrytě. Po instalaci obnovte hlasy a přehrajte ukázku. Název, který engine vystavuje Androidu, se může lišit od názvu Piper modelu. Nelze slíbit, že Google Speech Services nabízí hlas pojmenovaný Jirka. Android Voice nemá standardní údaj o pohlaví; mužský/ženský hlas aplikace neodhaduje podle názvu a nepřiřazuje postavám. Všechny offline české hlasy daných enginů jsou dostupné k volbě.

## Co aplikace skutečně dělá

- CameraX ImageAnalysis s KEEP_ONLY_LATEST; luminanční výřez odpovídá náhledu sdíleným ViewPortem, včetně otočení obrazu. OCR rozpoznává celý výřez a řadí řádky shora dolů a zleva doprava.
- Detektor stability čeká na několik shodných pozorování, sleduje interpunkci, dopisování, změnu a zmizení titulku. Nejisté nedokončené bloky může spojit přes další obrazovku, s omezenou dobou čekání.
- Offline Google ML Kit Translation překládá celý dokončený blok. Předzpracování sjednotí mezery, některé hovorové anglické tvary a chrání uživatelem zadaná jména.
- Jediný přehrávací worker čeká na `UtteranceProgressListener.onDone`. Používá výhradně `QUEUE_ADD`. Nová věta nikdy nevyvolá `stop()` ani `QUEUE_FLUSH`.
- Fronta zachovává pořadí i při opačném pořadí dokončení překladů. Má limit 40 přijatých vět a malé přechodné zadržení dokončených OCR bloků. Při zaplnění zastaví další analýzu a zobrazí upozornění; během pauzy mohou uniknout nové titulky z televize. Přijaté věty bez pokynu nemaže.
- Dva překladové workery mají timeout a jedno automatické opakování. Po přetrvávající chybě zůstává věta ve frontě a blokuje pozdější věty. Uživatel zvolí opakování nebo přeskočení. TTS má samostatný watchdog.
- Pomocný odposlech používá pouze `createOnDeviceSpeechRecognizer`. Nevrací se ke cloudovému rozpoznávání. Bez offline angličtiny zůstává plně dostupná kamera. Koncové výsledky s dostatečnou jistotou a časovou shodou mohou opravit zjevně poškozené OCR slovo nebo ojedinělou mezeru mezi shodnými slovy; mikrofon nikdy nevytváří vlastní položku fronty.
- Při českém TTS se odposlech vypne, následně krátce počká na dozvuk. Nepředstírá se hardwarové potlačení ozvěny, které systémový SpeechRecognizer neposkytuje aplikaci pod vlastní kontrolou.
- OCR pracuje přibližně 1–4,5× za sekundu podle změn obrazu a teploty, ne na každém snímku. Při kritické teplotě se relace pozastaví, při silném zahřátí se odposlech vypne.
- Ztráta externího zvukového zařízení pozastaví začínání dalších vět. Rozehraná věta se nechá dokončit a může po systémovém přesměrování doznít z telefonu. Pro pokračování uživatel potvrdí zvuk.

## Přesné limity

ML Kit je oficiální bezplatný lokální základ, **nikoli konverzační jazykový model**. Nedokáže zaručit přirozený slang, správné rodové vztahy ani význam každé herní repliky. Paměť posledních osmi dialogů je lokální a omezená; nejde o kontextový parametr ML Kit. Experimentální generativní překladač není součástí této verze. Podrobné srovnání kandidátů je v [TECHNOLOGY.md](docs/TECHNOLOGY.md).

Dokončení titulků je heuristika: dlouhá pauza uprostřed dopisování může vypadat jako konec a velmi krátký titulek zachycený jen jednou se zahodí jako nejistý. Opakování identického textu je potlačeno 18 s; zůstávající titulek se neopakuje ani po této době, nová skutečná promluva po zmizení a odstupu opět ano. Identická věta zopakovaná různými postavami rychleji než 18 s může být potlačena.

ASR není určeno pro nepřetržité několikahodinové rozpoznávání. Používá krátké relace při viditelných titulcích, pauzy a odstupy po chybách. Dostupnost angličtiny a údajů o důvěryhodnosti závisí na systému. Mikrofon telefonu nelze ve všech systémových ASR enginech vynutit místo mikrofonu Bluetooth sluchátek. Sladění je časová/lexikální heuristika, nikoli identifikace hlasu postavy.

Nezaručuje se konkrétní celková latence, výdrž baterie ani tepelný profil bez měření na POCO F7 Ultra. Hodnota uživatelského zpoždění je oddělená od OCR, překladu a přípravy TTS. Finální stav ověření je v [VALIDATION.md](docs/VALIDATION.md).

## Soukromí a životní cyklus

Snímky, video ani audio se neukládají. Titulky, fronta a krátká historie existují pouze v paměti procesu; ukončení procesu je odstraní. Ukládá se pouze výřez a nastavení. Android zálohy jsou zakázané. Kamera a mikrofon se při zastavení nebo odchodu do pozadí vypnou. Aktuální TTS věta se dokončí. Aplikace neprovozuje skrytou foreground service; pro překlad má zůstat otevřená. Displej během relace zůstává rozsvícený.

Nezbytná oprávnění: kamera; volitelně mikrofon, vyžádaný až při zapnutí; internet a stav sítě pro první stažení modelu. Není potřeba úložiště, Bluetooth scan/connect, poloha ani kontakty. ML Kit a nainstalované TTS/ASR služby mohou mít vlastní systémovou telemetrii; aplikace neobsahuje vlastní analytiku ani nahrávání médií.

## Sestavení

Požadavky: **JDK 21**, Android SDK platform 36 a build-tools 36.0.0. Gradle wrapper 8.13, AGP 8.13.1, Kotlin 2.1.21. Přidejte `sdk.dir=/cesta/k/android/sdk` do lokálního `local.properties`, nebo nastavte `ANDROID_HOME`.

```sh
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Pro produkční release použijte vlastní podpisový klíč, který není součástí zdrojů. Debug keystore je lokální sestavovací prostředek, není vložen do zdrojového archivu. Workflow `.github/workflows/android.yml` provede stejné kontroly a publikuje APK jako build artefakt po vložení projektu do GitHub repozitáře. Workflow zde není prohlašován za spuštěný na GitHubu.

## Zdroje a atribuce

Překlady poskytuje Google Translate prostřednictvím ML Kit on-device Translation. Vedle překladu je původní oficiální atribuce. [Google Translate](https://translate.google.com), [informace o službě](https://cloud.google.com/translate), [pravidla ML Kit](https://developers.google.com/ml-kit/language/translation/translation-terms).

Použité knihovny a licenční informace: [THIRD_PARTY.md](docs/THIRD_PARTY.md). Architektura a testovací postup: [ARCHITECTURE.md](docs/ARCHITECTURE.md), [TESTING.md](docs/TESTING.md).

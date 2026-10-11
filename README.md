# PRIME VOICE

Nativní testovací Android aplikace: mikrofon → Vosk (angličtina) → lokální ML Kit EN–CS → český Android TTS engine → sluchátka. Kotlin, Compose, Android 8+; první cílový telefon POCO F7 Ultra / Android 16 / ARM64.

**Stav 11. 10. 2026:** první testovací APK je skutečně sestavené. [Stáhnout APK pro ARM64 (39 MB)](https://github.com/hit9dagger/prime-voice/actions/runs/38133658912/artifacts/11700022352) · [úspěšný build](https://github.com/hit9dagger/prime-voice/actions/runs/38133658912) · [celý Android Studio projekt ZIP](https://github.com/hit9dagger/prime-voice/actions/runs/38133658912/artifacts/11700107032).

Prošly jednotkové testy, Kotlin compile, Android lint, podpis APK i ELF/ZIP zarovnání na 16 KiB. **Fyzický POCO ani celý hlasový řetězec na telefonu ještě otestované nejsou.** Nejde o dokončené oddělování více současných mluvčích; omezení jsou níže. Původní repozitář obsahoval jen README.

## Instalace pouze z telefonu

1. Otevřete [GitHub Actions](https://github.com/hit9dagger/prime-voice/actions), přihlaste se ke GitHubu a otevřete poslední **úspěšný** běh Android workflow pro větev `codex/prime-sync` (po sloučení `main`).
2. Dole v **Artifacts** stáhněte soubor APK. Pokud telefon stáhne ZIP, rozbalte jej v aplikaci Soubory. APK nebude existovat, pokud sestavení skončilo chybou.
3. Otevřete APK a povolte instalaci z použitého prohlížeče/správce souborů. Jde o testovací debug podpis. Není třeba účet v aplikaci ani placené API.
4. Pro neuronovou češtinu nainstalujte samostatný [Sherpa / Piper Jirka ARM64 TTS engine 1.13.8](https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/tts-engine-new/1.13.8/sherpa-onnx-1.13.8-arm64-v8a-ces-tts-engine-vits-piper-cs_CZ-jirka-medium.apk). Odkaz pochází z [oficiální dokumentace](https://k2-fsa.github.io/sherpa/onnx/tts/all/Czech/vits-piper-cs_CZ-jirka-medium.html). Engine obsahuje model; nevyžaduje server. Instalaci tohoto cizího APK na POCO jsme zatím neotestovali.
5. Spusťte PRIME VOICE, stáhněte modely (ASR přibližně 42 MB, překlad přibližně 30 MB), připojte sluchátka, povolte mikrofon a stiskněte START.
6. Aplikace upřednostní nainstalovaný Sherpa engine. Jinak použije dostupný **lokální český hlas** systémového enginu. Systémový hlas není automaticky označován za neuronový. Chybějící hlas lze doplnit v systémovém nastavení převodu textu na řeč.
7. Zkuste nejprve jednu krátkou anglickou větu bez hudby. Pak otestujte režim letadlo se znovu zapnutým Bluetooth. Při potížích exportujte v aplikaci diagnostiku JSON.

Při dalším debug buildu může vzniknout jiný testovací podpis: Android pak vyžaduje odinstalaci předchozího buildu, čímž odstraní jeho modely a frontu. Stabilní distribuční podepisování zatím není nastaveno. Artefakty Actions mají omezenou dobu uchování.

## Co zdrojový kód implementuje

- AudioRecord 16 kHz / mono / PCM16, upřednostnění mikrofonu telefonu, omezenou vstupní frontu a explicitní evidenci nezpracovaných bloků.
- Skutečnou lokální Vosk inferenci a její detekci konce promluvy. Dlouhé úseky se rozdělují. Nejde o model oddělování hlasu od hudby.
- Skutečný lokální překlad ML Kit EN → CS po stažení modelu.
- Syntézu přes Android TTS do WAV a samostatné AudioTrack stopy. Ověřuje se lokální český hlas a dostupnost sluchátek.
- PRIME SYNC: společný monotónní čas, adaptivní plánování a samostatné FIFO stopy mluvčích. Různé stopy mohou hrát současně, stejná stopa dokončí předchozí větu. Nejvýše čtyři současné výstupy, ostatní čekají.
- Maximální automatickou rychlost 1,35× s výškou 1,0 přes Android PlaybackParams.
- Omezenou soukromou diskovou frontu **24 dokončených překladů**. Při zahlcení se pozastaví příjem nové řeči; již přeložené věty se kvůli novým nemažou.
- Experimentální podobnost mluvčích pomocí Vosk speaker embeddings, stabilní ID během relace, konzervativní neznámý mluvčí a ruční změnu přiřazeného dostupného hlasu.
- Foreground service, trvalou notifikaci se STOP, export technické diagnostiky a test ze skutečného WAV souboru přes stejnou pipeline.

Samotná existence kódu nepotvrzuje správné chování na telefonu. Automatické testy jsou popsány níže a výsledky se evidují v `docs/STATUS.md`.

## Co dokončeno není

- **Oddělování ani spolehlivá detekce současně mluvících lidí.** Monofonní Vosk může při překryvu ztratit slova nebo spojit promluvy. Nesmí se to vydávat za diarizaci jednotlivých souběžných zdrojů. Takový vstup zatím není podporovaným testem přesnosti.
- **Tři odlišné neuronové české hlasy nejsou přibaleny.** Ověřený Piper Jirka má jeden hlas. Pokud engine nabízí jen jeden lokální český hlas, Speaker ID budou rozlišená, ale hlas bude stejný. Změna tempa není klonování hlasu.
- Spolehlivá identita krátkých, křičených nebo překrytých replik; Vosk vrací jeden embedding celého segmentu. Výchozí nastavení potřebuje přibližně čtyři sekundy řeči.
- Změna chybných hlasových profilů jejich slučováním, skutečné voice cloning a cross-lingual voice conversion.
- Ověření latence, kvality, teploty, Android 16, Bluetooth a zamknuté obrazovky na fyzickém POCO. Žádná konkrétní rychlost není garantována.
- Zatím není ověřena celá inference v automatickém Android integračním testu. JVM testy časování a parserů nepředstavují test AI modelu.

## PRIME SYNC a limity záruk

Dokončený překlad se uloží před zařazením do syntézy. Přehrání jej odstraní z fronty. Nový překlad nesmí přerušit starý. Při telefonním hovoru, odpojení sluchátek, STOP nebo restartu může být nedokončená věta přehrána znovu od začátku; nelze zaručit nepřerušený zvuk při zásahu Androidu.

Nelze současně garantovat konečnou paměť, nízké zpoždění a zpracování nekonečného toku rychlejšího než telefon. Po naplnění rezerv aplikace **přestane přijímat další řeč** a dopřehrává frontu. Nové věty v tomto období nejsou překládány, což je viditelné ve stavu a diagnostice. Výpadek napájení právě před diskovým zápisem ani selhání úložiště nelze překonat softwarovým slibem.

Časy jsou měřeny přes monotónní Android hodiny. Doba do spuštění AudioTrack zahrnuje frontu a syntézu, ale **ne skutečné akustické zpoždění Bluetooth sluchátka**.

## Soukromí

Zvuk a hlasové profily zůstávají v RAM. Aplikace zvuk mikrofonu nezapisuje ani neodesílá na server. Pro obnovení nepřehraných vět se jejich text a vygenerovaný český zvuk dočasně ukládají do soukromého úložiště bez systémového zálohování. Po úspěšném přehrání se mažou; STOP je zachová. Uživatel je může vědomě vymazat v nastavení. Historie na obrazovce je jen v paměti a je omezená.

Diagnostický JSON neobsahuje nahrávky, přepisy, překlady, hlasové vektory, jména Bluetooth zařízení ani identifikátory telefonu. ML Kit může mít vlastní provozní telemetrii SDK; text překládá lokálně. Internet je potřebný pro první instalaci modelů.

## Technologie, modely a licence

- Vosk Android 0.3.75 / JNA 5.18.1, Apache 2.0 / JNA LGPL 2.1 nebo Apache 2.0. Rychlý `vosk-model-small-en-us-0.15`: 41 205 931 B, Apache 2.0. Volitelný `vosk-model-spk-0.4`: 13 869 103 B, Apache 2.0. Metadata: [oficiální katalog](https://alphacephei.com/vosk/models/model-list.json). HTTPS, přesná velikost a katalogové MD5 kontrolují přenos; MD5 není moderní důkaz autenticity. Rozbalování kontroluje cesty a limity. Poškozený download se nesmí načíst.
- Whisper.cpp má kvalitní Android ARM64 cestu, ale pro tento první build volíme lehčí průběžný Vosk. Přesnost ve hře se musí změřit; malý model může chybovat více než Whisper.
- Google ML Kit Translate 17.0.3: bezplatný proprietární Android SDK, nikoli open-source engine. EN → CS běží lokálně. Velikosti a kontrolní součty spravuje SDK; aplikace nevydává odhad 30 MB za přesnou velikost. [Podmínky](https://developers.google.com/ml-kit/language/translation/translation-terms) a [atribuce](https://docs.cloud.google.com/translate/attribution).
- Marian / OPUS-MT EN-CS existuje, ale vyžadovalo by samostatný Android runtime a tokenizer; není zde nahrazen slovníkem.
- Piper Jirka medium: 1 hlas, 22 050 Hz, dataset podle model card CC0. Samostatný Sherpa TTS engine obsahuje eSpeak a GPL závislosti; **není staticky přilinkován k této aplikaci s ML Kit**. Vlastní redistribuce enginu vyžaduje splnění jeho licencí. Kvalita a kompatibilita konkrétního externího APK nejsou potvrzené tímto buildem.
- Herní kontext, překřikování, jména a slang nejsou garantovány. Aplikace ukazuje skutečný anglický přepis a skutečný překlad, nikdy předpřipravené ukázkové odpovědi.

## Sestavení

Android Studio s SDK 36, JDK 17. Wrapper Gradle 8.11.1, Android Gradle Plugin 8.10.1, Kotlin 2.1.21. Cílová ABI arm64-v8a.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK je v `app/build/outputs/apk/debug/`. GitHub Actions provede stejné kontroly a navíc ověří podpis a 16KiB zarovnání nativních knihoven. Standardní veřejný GitHub runner nevyžaduje placenou službu; platí obecné limity GitHubu.

## Testování

JVM testy kontrolují čistou logiku časové osy, pořadí a rychlosti, shodu hlasových vektorů a WAV parser. Nevyužívají fingované AI výsledky jako doklad přesnosti modelů. Postup telefonu a neprovedené integrační testy: [docs/TESTING.md](docs/TESTING.md).

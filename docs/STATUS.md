# Ověřený stav PRIME VOICE

Aktualizováno 11. 10. 2026.

Původní revize `79c5028` obsahovala jen README. Nyní je v repozitáři skutečný Android projekt a instalační APK.

## Skutečně provedené kontroly

[GitHub Actions 38133658912](https://github.com/hit9dagger/prime-voice/actions/runs/38133658912), aplikační revize `ff4bf169e1d90350795f048a69329630074e8d93`: **success**, Gradle **BUILD SUCCESSFUL**.

- Kotlin/Android compile: prošel.
- `:app:testDebugUnitTest`: prošel; 49 testovacích metod ve čtyřech sadách (WAV vstup, TTS WAV, plánování PRIME SYNC, přiřazení embeddings).
- `:app:lintDebug`: prošel bez chyb; varování nejsou vydávána za chyby ani skryta baseline souborem.
- `:app:assembleDebug`: skutečně vytvořil APK.
- Všechny čtyři ARM64 knihovny `libvosk.so`, `libjnidispatch.so`, `libtranslate_jni.so` a `libandroidx.graphics.path.so`: prošly kontrolou ELF na 16KiB stránky.
- ZIP alignment: prošel. APK podpis: ověřený v2.
- Publikovaný artefakt: `PRIME-VOICE-debug-arm64.apk`, **39 083 401 B**, ID `11700022352`.
- SHA256 artefaktu: `51329744f21ff65fcdb8bef25bc946071e3aef81efd5f60dd6b20b362d18d2e6`.
- [APK](https://github.com/hit9dagger/prime-voice/actions/runs/38133658912/artifacts/11700022352) a [zdrojový ZIP](https://github.com/hit9dagger/prime-voice/actions/runs/38133658912/artifacts/11700107032) mají uchování do **18. 10. 2026**. Stahování vyžaduje přihlášení na GitHub.

První běh 38132767374 zastavil lint kvůli konstantě v diagnostice audio zařízení. Oprava byla ověřena druhým úspěšným během; první neúspěch se nevydává za úspěšný distribuční build.

## Implementace versus test na telefonu

| Část | Zdroj / automatické ověření | Fyzický POCO |
|---|---|---|
| Kotlin/Compose + microphone service | kompiluje, lint prošel | neprovedeno |
| Vosk EN ASR, skutečný model download | integrace kompiluje; inferenční test Androidu neproveden | neprovedeno |
| Lokální ML Kit EN-CS | integrace kompiluje; inferenční test Androidu neproveden | neprovedeno |
| Externí Piper / offline systémové TTS | integrace kompiluje; WAV parser otestován | neprovedeno |
| PRIME SYNC a samostatné FIFO stopy | časování a pořadí JVM testy prošly | přehrávání a Bluetooth neprovedeno |
| Speaker embeddings | matematika konzervativního přiřazení otestována; experimentální | přesnost neověřena |
| Fronta hotových překladů | implementace a kontrola životního cyklu; skutečné crash/disk-full testy zbývají | neprovedeno |
| Diagnostický JSON bez konverzací | implementace a kontrola kódu | export neproveden |
| APK | soubor skutečně vytvořen, podepsán, zkontrolován a nahrán | instalace neprovedena |
| Oddělení současné původní řeči | **neimplementováno** | nepodporováno |
| Tři odlišné neuronové české hlasy | **nedodány**; ověřený Jirka má jediný hlas | neprovedeno |

JVM testy s časovými značkami a vektory nejsou vydávány za testy rozpoznávání skutečných lidí.
Model Vosk není zdrojový separátor. Externí engine Jirka nemůže vytvořit tři různé hlasy.
Přehrávání různých stop je implementované, ale jeho skutečné souběžné chování vyžaduje telefonní test.

## Co má ověřit první uživatel

Instalace APK → stažení modelů → instalace lokálního českého hlasu → sluchátka → skutečná anglická věta → český zvuk.
Pak režim letadlo, zamknutí, odpojení sluchátek, hovor a export diagnostiky. Podrobnosti: [TESTING.md](TESTING.md).

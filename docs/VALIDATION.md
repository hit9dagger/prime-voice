# Výsledky ověření

## CAMERA 2.0

Aktuální pravidla, skutečný neúspěšný test Qwen a hranice ověření Gemma jsou v [CAMERA-2.0.md](CAMERA-2.0.md). Přiložené reporty a GitHub Actions patří sestavenému commitu. Fyzické 4GB zařízení ani POCO/Bluetooth nebyly k dispozici.

## Historické lokální ověření verze 1.0

Stav před zveřejněním verze 1.0; nepopisuje nové APK 2.0.

## Provedené kontroly

- `:core:test`: **41 testů, 0 selhání, 0 chyb**. Jde o skutečně spuštěné Kotlin/JVM testy, nikoli jen připravené testovací soubory.
- `:app:assembleDebug`: **BUILD SUCCESSFUL**. APK pro arm64-v8a (POCO) i x86_64 bylo skutečně sestaveno.
- `:app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**. Připraveny instrumentované testy skutečného bundled OCR a spuštění Activity.
- `:app:lintDebug`: **0 chyb**, 17 upozornění. Upozornění se týkají novějších dostupných verzí závislostí a doporučení Kotlin KTX; neprohlašujeme lint za bez všech upozornění.
- `apksigner verify --verbose`: **Verifies**, podpis APK v2 je platný; jeden vývojový podpis.
- `zipalign -c -P 16 4`: úspěšně ověřeno.
- Všech pět nativních knihoven arm64-v8a má PT_LOAD zarovnání **16384 bajtů**.
- Finální APK po čistém zabalení má přibližně **60,6 MiB**. Je podepsané debug klíčem, připravené pro přímou instalaci, bez publikace v Google Play.

## Hranice ověření

POCO F7 Ultra, televizor ani fyzické Bluetooth zařízení nejsou k prostředí připojeny. Neproběhlo měření reálné latence, výdrže, teplot, přesnosti na konkrétní televizi ani subjektivní kvality českého hlasu. Offline ASR je závislé na systémovém rozpoznávači a jeho jazycích.

Testy fronty používají řízený překladač a hlas, aby ověřily souběh, pořadí, chybové stavy a časové rezervy. Nejsou důkazem kvality strojového překladu. Bluetooth test v core ověřuje reakci fronty na ztrátu výstupu, nikoli rádio a systémový routing fyzického telefonu.

Podrobný postup převzetí na telefonu: [TESTING.md](TESTING.md). Automatický GitHub Actions workflow je přiložen; v tomto úkolu nebyl spuštěn na GitHubu.

Přesný SHA-256 APK a strojově čitelný přehled jsou v přiloženém `overeni.json` a `SHA256SUMS.txt`.

Instrumentované Android testy jsou sestavené, ale nepodařilo se je spustit. Emulátor v tomto omezeném prostředí částečně naběhl a ohlásil Android 16; před instalací aplikace skončil a připojení bylo odmítnuto. Spuštění aplikace ani skutečná Android inference OCR tedy nejsou ověřené. Tato překážka není označena jako úspěšný test.

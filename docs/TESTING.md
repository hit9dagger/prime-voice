# Testovací plán pro POCO F7 Ultra / Android 16

## Automaticky v GitHub Actions
- Gradle unit tests: časová osa, konzervativní přiřazení speaker embeddingu, limity rychlosti, FIFO, WAV parser.
- Kotlin/Android compile a lint.
- Podepsané debug APK, apksigner, 16KiB ELF a ZIP alignment.

Výsledky jsou dostupné v konkrétním běhu Actions. Testy scheduleru používají časové vstupy, nikoli předstírané AI výsledky.

## Telefon – zatím neprovedeno
1. Nainstalovat APK, Piper Jirka engine, stáhnout ASR/MT, připojit sluchátka. Povolit mikrofon.
2. Přehrát skutečnou anglickou větu: porovnat přepis, český text a slyšitelný hlas. Opakovat v režimu letadlo s Bluetooth.
3. Soubor WAV PCM16, mono 16 kHz přes TEST ZE SOUBORU; nekompatibilní soubor musí skončit chybou, nikoli falešným přepisem.
4. Tři po sobě jdoucí věty, rychlé střídání, dlouhá replika >10 s. Stejný mluvčí zachová pořadí; nedojde k automatickému rušení hotového překladu.
5. Zapnout CHARACTER VOICE, nahrát alespoň 4 s čisté řeči každého mluvčího. Opakování po minutě. Krátké/nejasné repliky se smějí označit jako unknown. Změnit přiřazený hlas; zkontrolovat další repliku.
6. S enginem obsahujícím nejméně 3 skutečné české hlasy otestovat 3 různé stopy. Piper Jirka s jedním hlasem tento test **nemůže** splnit.
7. Dva překlady z různých spolehlivě určených mluvčích: ověřit překryv výstupů a že se vzájemně nezastavují. Výška hlasu při 1,35× se nemá měnit.
8. Dva současní angličtí mluvčí: **nepodporovaný vstup pro source separation**. Zaznamenat chyby přepisu, nepovažovat úspěch za doklad oddělování.
9. Odpojit Bluetooth během přehrávání. Zvuk nesmí pokračovat reproduktorem; fronta zůstane a po obnovení může větu zopakovat. Ověřit mikrofon telefonu, nikoli headset HFP/SCO.
10. Přijmout telefonní hovor, zamknout obrazovku, přejít do jiné aplikace, stisknout STOP v notifikaci. Ověřit zhasnutí mikrofonního indikátoru a uvolnění zdrojů.
11. HyperOS nastavení baterie: vyzkoušet běžnou optimalizaci a podle skutečného chování ručně povolit neomezovaný běh pro PRIME VOICE. Aplikace systémové limity neobchází.
12. Dlouhodobě zahltit vstup: max. 24 rezervovaných překladů, stav pozastavení příjmu, žádné mazání dokončených položek. Uvolnění kapacity obnoví příjem.
13. Nedostatek místa/chybějící model/poškozený download/ukončení procesu: srozumitelná chyba, žádné načtení nedokončeného modelu; uložená fronta se při dalším START obnoví.
14. Exportovat JSON a ručně zkontrolovat nepřítomnost větného obsahu, hlasových vektorů a názvů Bluetooth zařízení. Při sdílení poslat JSON + popis kroků + čas chyby.

Měřit ASR, MT, TTS a start AudioTrack. Akustickou latenci Bluetooth je nutné změřit externě; údaj aplikace ji neobsahuje. Výkon bez takového testu neodhadovat jako garantovaný.

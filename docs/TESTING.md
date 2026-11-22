# Testování

## Automatická logika

Spusťte `./gradlew :core:test`. Testy nepoužívají internet ani Android telefon. Virtuální čas umožňuje přesně prověřit pořadí a rezervu:

- Dva řádky jako jeden blok, postupné dopisování, dlouho zobrazené titulky, rychlá výměna dvou bloků.
- Stejný titulek, krátké zmizení, skutečné opakování po 18s okně.
- Pokračování na další obrazovce, nesouvisející další replika, timeout neúplného bloku, OCR s malou jistotou.
- Protiklad mezi OCR a ASR, hluk, neznámá ASR jistota, starý/částečný výsledek, oprava poškozeného slova, zákaz vkládání záporu, vypnutí evidence mikrofonu.
- **První česká věta hraje, během ní přijdou další tři: přehrají se všechny ve správném pořadí bez souběhu.**
- Pomalý první překlad a rychlý druhý, rezerva 0/3/10 s, žádná další rezerva po vyprázdnění fronty.
- Zastavení, ruční přeskočení, opakování poslední věty, přechodná chyba, timeout, TTS chyba.
- Ztráta povolení zvukového výstupu jako simulace události Bluetooth, kapacita fronty při 1000 příchozích blocích.
- Omezená kontextová paměť, ochrana jmen, odmítnutí porušených značek, meze výřezu.

`./gradlew :app:lintDebug :app:assembleDebug` kontroluje Android kód, manifest, zdroje a sestavitelnost. Testy fronty používají řízený fake TTS/translator, **nepředstavují měření kvality ML Kit nebo skutečný Bluetooth test**.

## Převzetí na POCO F7 Ultra / Android 16

1. Nainstalujte APK, připravte modely a ověřte ukázku českého offline hlasu. Zapněte režim letadlo (Bluetooth lze znovu povolit) a proveďte kamerový překlad.
2. Zobrazte na televizi dvouřádkový anglický dialog, statický titulek 60 s, postupně dopisovanou větu a dvě rychlé repliky. Zkontrolujte jednu promluvu na dokončený blok a vynechání HUD textu.
3. Označte jiný výřez, aplikaci zastavte a znovu otevřete. Ověřte uloženou oblast, ostření, zoom a expozici. Zkontrolujte výřez na šířku i na výšku.
4. Vytvořte během dlouhé české věty tři další titulky. Poslechem ověřte pořadí a dokončení první věty. Vyzkoušejte Vyprázdnit během přehrávání.
5. Porovnejte rezervu 0 a 10 s; první věta čeká podle nastavení, další už ne. Diagnostika rozlišuje dobu OCR, překladu a TTS přípravy.
6. Zapněte/vypněte mikrofon za běhu, zamítněte jeho oprávnění a ověřte, že kamera funguje. Mluvte česky do místnosti a porovnejte chybné/odlišné ASR s titulkem. Žádný samostatný ASR výsledek nesmí vytvořit frontu.
7. Vyzkoušejte reproduktor, A2DP/BLE sluchátka a Bluetooth reproduktor. Během promluvy odpojte zařízení: nová věta čeká na potvrzení. Přesměrování rozmluvené věty řídí systém.
8. Odejděte do jiné aplikace, zamkněte telefon a zastavte překlad. V indikátorech Androidu musí kamera i mikrofon zhasnout. Po návratu musí být aplikace zastavená.
9. Dvou- až čtyřhodinový běh: měřte teplotu, baterii, skutečnou latenci, počet duplicit, délku fronty. Ověřte upozornění při přetížení a regulaci při tepelném stavu MODERATE/SEVERE. Nezahřívejte telefon uměle kvůli testu.
10. Vyzkoušejte slang, jména a zápory na skutečné hře. Zaznamenejte chyby překladu odděleně od OCR. Bez této zkoušky nelze slíbit kvalitu profesionálního dabingu.

## Co není možné automaticky zaručit

Hardwarové ostření, televizní obnovovací frekvence, přirozenost konkrétního hlasu, přepínání konkrétního Bluetooth zařízení, přesnost ASR v místnosti a výdrž POCO vyžadují fyzické zařízení. Emulátor ani unit testy je nenahrazují.

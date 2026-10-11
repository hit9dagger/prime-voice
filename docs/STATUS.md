# Stav implementace

Aktualizováno 2026-10-11.

Výchozí revize 79c5028 obsahovala pouze README. Ve větvi codex/prime-sync je skutečný Android zdrojový projekt. První běh Actions 38132767374 (revize 7402cb7)
zkompiloval Kotlin, provedl testDebugUnitTest bez chyby a vytvořil debug APK na runneru.
Celý běh však selhal na jedné chybě lint WrongConstant v diagnostice audio zařízení; APK se proto nenahrálo.
Oprava a opravy obnovy fronty se ověřují dalším během. Za úspěšný distribuční build tento první běh nepovažujeme.

| Část | Zdrojový kód | Automatické ověření | Fyzický POCO |
|---|---|---|---|
| Kotlin/Compose projekt + microphone service | implementuje se | čeká na CI | neprovedeno |
| Vosk EN ASR + model download | implementuje se | čeká na build | neprovedeno |
| Lokální ML Kit EN-CS | implementuje se | čeká na build | neprovedeno |
| Externí Piper / offline Android TTS | implementuje se | čeká na build | neprovedeno |
| PRIME SYNC, FIFO a souběžné AudioTrack | implementuje se | čeká na JVM testy | neprovedeno |
| Speaker embeddings | experiment | čeká na testy matematiky | neprovedeno |
| Oddělení současné originální řeči | neimplementováno | žádné | neprovedeno |
| Více odlišných neuronových českých hlasů | záleží na enginu, Jirka pouze jeden | žádné | neprovedeno |
| Diagnostika bez obsahu konverzací | implementuje se | kontrola kódu | neprovedeno |
| APK | zatím nevzniklo | čeká na CI | nenainstalováno |

Úspěšný compile/JVM test se nesmí zaměnit s ověřením inference na Androidu. Rozšířené funkce se nemají označit za dokončené před základním testem věta → překlad → zvuk.

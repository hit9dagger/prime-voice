# Volba bezplatných lokálních technologií

Ověřeno proti veřejné dokumentaci dne 22. 11. 2026. Následující srovnání není měření na POCO F7 Ultra.

| Komponenta | Volba | Důvod a omezení |
|---|---|---|
| OCR | ML Kit Text Recognition v2, bundled Latin | Model v APK, oficiální Android SDK, bez síťového OCR. Malý text, blikání TV a perspektiva mohou snižovat přesnost. |
| Překlad | ML Kit Translation EN→CS | Oficiální bezplatná lokální inference s prvním stažením modelů. Podporuje češtinu. Nejde o generativní kontextový překladač. |
| Otevřená alternativa | Helsinki-NLP/opus-mt-en-cs | Apache-2.0, Marian Transformer + SentencePiece. Vyžadoval by port/tokenizaci a benchmark ONNX/Android. Bez doloženého zlepšení rychlosti i kvality nebyl nasazen. |
| Velké lokální modely | nenazazeny | Není ověřena kvalita českého slangu, rychlost ani souběžná paměť/teplotní zátěž s OCR na cílovém zařízení. Není přidán nefunkční přepínač ani simulovaná implementace. |
| TTS | Android TextToSpeech | Skutečná enumerace enginů/hlasů, jen offline české hlasy, dokončení přes callback. Neexistuje standardní údaj o pohlaví. |
| Jirka | Piper + sherpa-onnx Android TTS engine | Reálně dostupná bezplatná volba instalovatelná uživatelem. Samostatné APK závisí na architektuře telefonu a verzi enginu. |
| ASR | Android on-device SpeechRecognizer | Bez API klíče a bez cloudového fallbacku. Volitelný, nemusí být dostupná angličtina, confidence ani stabilní dlouhodobá relace. |

ML Kit je zde zvolen jako proveditelný základ, **ne jako prokazatelně nejlepší překladový model pro všechny herní dialogy**. Dokumentace jej doporučuje pro běžný jednodušší překlad. Není doloženo, že by otevřený kandidát na tomto telefonu podával lepší výsledky při současném OCR a TTS. Další vývoj má začít A/B měřením skutečných replik a teplot, ne slibem vyšší kvality.

Paměť dialogů nepředstírá nepodporovaný `context` parametr. Implementováno je předzpracování celých bloků, navazování obrazovek, normalizace vybraných anglických tvarů a ochrana uživatelem zadaných jmen. Neimplementováno je spolehlivé rozlišení pohlaví postavy, hlasové identity a volný kontextový přepis slangu.

## Primární zdroje

- https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- https://developers.google.com/ml-kit/language/translation
- https://developers.google.com/ml-kit/language/translation/android
- https://developers.google.com/ml-kit/language/translation/translation-language-support
- https://developers.google.com/ml-kit/language/translation/translation-terms
- https://huggingface.co/Helsinki-NLP/opus-mt-en-cs
- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://developer.android.com/reference/android/speech/tts/TextToSpeech
- https://developer.android.com/reference/android/speech/tts/Voice
- https://huggingface.co/rhasspy/piper-voices/blob/main/cs/cs_CZ/jirka/medium/MODEL_CARD
- https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html
- https://play.google.com/store/apps/details?id=com.google.android.tts
- https://f-droid.org/en/packages/com.github.olga_yakovleva.rhvoice.android/

Google Speech Services je legitimní systémová možnost, ale konkrétní český mužský hlas ani obchodní jméno Jirka v něm nebyly potvrzeny. RHVoice je otevřená alternativa s češtinou; výběr zůstává podle skutečné instalace a poslechu. Placené Vocalizer/SpeechTech hlasy ani neoficiální webová překladová API nejsou přibalena.

# Knihovny a atribuce

- Kotlin a kotlinx.coroutines: Apache License 2.0, JetBrains a přispěvatelé.
- AndroidX, Jetpack Compose, CameraX, Lifecycle, Activity, Core: Apache License 2.0, Android Open Source Project a přispěvatelé.
- Google ML Kit a transitivní Google Play Services komponenty: jejich distribuční podmínky Google, viz https://developers.google.com/ml-kit/terms a licence v Maven artefaktech. Jde o oficiální SDK.
- JUnit 4 (pouze testy): Eclipse Public License 1.0.
- Gradle wrapper: Apache License 2.0.
- Google Translate attribution: původní `white-regular@3x.png` z https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip, použitý podle https://docs.cloud.google.com/translate/attribution. Logo není značkou PRIME VOICE a nevyjadřuje podporu aplikace ze strany Google.

Piper/Jirka, sherpa-onnx, RHVoice ani jiné hlasové enginy nejsou vloženy do tohoto APK. Uživatelská instalace má samostatné licence. Karta Jirka uvádí dataset CC0, runtime a fonetizace mají vlastní licenční podmínky. Tento projekt nepřebaluje cizí engine do vlastní aplikace.

Google Translate, Android a PlayStation jsou značky svých vlastníků. PRIME VOICE není oficiální produkt Sony ani vydavatele hry.

## Volitelná lokální inference 2.0

LiteRT-LM 0.16.1: Apache-2.0, https://github.com/google-ai-edge/LiteRT-LM . Licence a NOTICE jsou součástí distribuované knihovny; modely se do APK nebalí. Qwen3 0.6B (Apache-2.0) byl testován mimo APK a v této verzi není povolen. Gemma 3n používá podmínky Gemma: https://ai.google.dev/gemma/terms . Uživatel získává model přímo od vydavatele po přijetí jeho podmínek.

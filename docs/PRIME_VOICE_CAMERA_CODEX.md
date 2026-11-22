# PRIME VOICE CAMERA — Codex implementation specification

## Objective
Extend existing PRIME VOICE Android Kotlin app with a **camera-based** English subtitle → Czech dubbing mode. Preserve the current microphone/Vosk mode and its tests. Target POCO F7 Ultra, Android 16. No software on TV or PS5. TV retains original audio; phone outputs Czech TTS via its selected media route (Bluetooth speaker when connected).

## Mandatory implementation sequence
1. Audit existing Gradle, app, CI, permissions, architecture, and tests. Report what can be reused. Do not delete microphone functionality.
2. Add CameraX Preview + ImageAnalysis with lifecycle management, backpressure KEEP_ONLY_LATEST, explicit ImageProxy.close(), throttled OCR, adjustable zoom/exposure, screen-on while active. Add runtime CAMERA permission.
3. Implement ML Kit Latin Text Recognition on real camera frames, user-adjustable subtitle ROI with correct rotation/preview-to-image coordinates. Exclude all text outside ROI. Show live OCR text and errors.
4. Subtitle stabilizer: normalize OCR, debounce near-duplicates, track evolving lines, wait for punctuation/quiet interval/timeout, combine split sentences conservatively, preserve original text, cap buffers and retain last 20 subtitles. Never fabricate unseen text.
5. Translate with actual ML Kit EN→CS model download and readiness state, asynchronous cancellable translation, strict FIFO IDs, offline-after-download behavior, errors/retry. Preserve translation context separately; ML Kit translation itself is sentence-based, so never claim it uses conversational context. Optionally provide a separate opt-in context model later.
6. Android TextToSpeech: enumerate genuine installed Czech voices and their metadata; allow manual voice selection for two profiles only if distinct voices are actually available. Detect explicit speaker labels, persist manual character mappings, use 'unknown' otherwise; never infer sex as certain from text. Queue utterances sequentially, with adjustable rate, pauses, and delay 3–30 s. Do not overlap.
7. Compose Czech UI: camera preview, draggable ROI, START/STOP, original EN, translated CS, queue state, delay, voice selection, translation readiness, Bluetooth audio route status, GTA/story profile, diagnostics and test mode.
8. Add unit tests for duplicate/partial/split subtitles, fast dialogue, OCR errors, speaker changes, FIFO ordering, delayed output, TTS failure and disconnection. Instrumentation or device test must demonstrate actual camera→OCR→translation→TTS. Mock tests alone are insufficient.
9. Add GitHub Actions build/test/lint and upload debug APK artifact. Fix failures and document exact build links, version, dependencies, permissions, battery/thermal constraints and POCO setup. Do not claim physical device verification without a real device run.

## Key constraints
- Prefer local, zero per-use API fees. ML Kit model may require initial download; document size/availability and licensing. No secrets in repository.
- No paid API or cloud transmission without explicit opt-in and cost disclosure.
- Use foreground service only where compliant with Android camera foreground service restrictions; do not promise background camera capture or operation with display off.
- Bluetooth output uses Android audio routing; do not claim reliable programmatic forcing to a specific Bluetooth device if platform does not permit it.
- Camera-based subtitle capture is limited by visual readability, game subtitle availability, screen glare, motion blur and frame timing.
- User wants complete, accurate sentences, not word-by-word speech. Latency is configurable; when subtitles disappear before sentence completion, prefer accurate conservative buffering and show uncertainty.
- Real Czech male/female voices are engine-dependent; if missing, expose setup instructions rather than inventing voices.
- Avoid unbounded queues, thermal load, and duplicate processing. Never mark tasks complete until end-to-end verification is documented.

## Acceptance criteria
A real English subtitle displayed on an external TV is captured from a phone camera, displayed as recognized English, translated to Czech by an actual on-device model, and automatically spoken through Android TTS, while original PS5 audio continues on TV. Repeat subtitles do not repeat speech. Status and failure modes are visible. A buildable APK is produced by CI, with manual POCO acceptance test still required.

# TRIAGE — On-device verification record

Device: Samsung `RZGYC1J0PKJ`, Android 16-class, arm64. Build: debug APK from this repo,
installed via `./gradlew installDebug`. Evidence gathered via `adb`, `run-as` DB/file reads, and
screenshots.

## ✅ Verified on-device (hard evidence)

### Automatic model provisioning (Part A)
- On first launch the app auto-downloaded the real vehicle/object detector into its private
  storage with no user action:
  `files/models/vehicle_det.tflite` = **6,938,269 bytes**, SHA-256
  `020d9f0a…dd9d` — byte-exact match to the coral SSD-MobileNet-v1-COCO model declared in
  `assets/models/registry.json`. Provisioning runs off the analysis path; analysis still works
  offline. The four fleet-specific heads have no public model and remain classical (registry shows
  this per head), activating automatically if a model URL is added.

### Guided capture → quality gate → hash → analysis (flow #1)
- A full 12-station walkaround (`6e744ba9`) produced **12 photo rows**, each with a non-empty
  SHA-256 and `qualityPassed=1`, original full-res JPEGs (e.g. FRONT = 9,114,787 bytes) stored
  under `files/inspections/<id>/`.
- Background analysis wrote **83 findings** across all four heads, each tagged with engine
  (`classical-cv`), `diffStatus=FIRST_RECORD` (no prior walkaround), zone, severity, confidence:
  - DAMAGE → `ANOMALY` (honest change-detector, low confidence 0.35) — not a fake classifier.
  - CLEANLINESS → per-zone + overall scores, low-confidence (no baseline enrolled yet).
  - LAMP → `LAMP_INTACT` / `LAMP_FOGGED`; TYRE → `TYRE_OK`.

### Evidence integrity (capture-time hashing)
- Pulled the stored FRONT original and re-hashed it:
  re-hash `f2886c2188…d515e94` == recorded `sha256` exactly. The stored original is untouched and
  analysis ran on a downscaled copy, as designed.

### UI / runtime
- App builds, installs, launches with **no `FATAL EXCEPTION`** across the session.
- Login (local PIN) works; capture screen renders the ghost overlay, dashed framing rect, lamp
  polygons, the powered-lamp prompt ("If dark: switch on headlamps…"), and the 12-station strip.
- Fixes applied + confirmed: vehicle **auto-selects** so *Start inspection* is immediately enabled
  (was disabled until a manual card tap); INCIDENT/driver-disputed records now route to the
  supervisor dispute queue; multi-permission request (camera+location+notifications); low-storage
  warning on home.

### Classical-head tuning (from the live findings)
- Damage no-reference grid-outlier: raised z-threshold 2.2→2.8 and capped to the 2 strongest cells
  per zone (was flooding a clean first-record with low-confidence candidates).
- Lamp `FOGGED` false-positive on dark/uniform regions: gated on brightness (`meanV > 0.40`), since
  fog is a milky *bright* surface.
- Cleanliness with no baseline now reports a score only (not "DUST/CLUTTER"), so the dataset
  exhaust isn't mislabeled without a reference; mud-splash (absolute brown blobs) still types.

## ✅ Phase 3 — advanced features + UI/UX (built, installed, smoke-tested)

All four bundles compile cleanly and install (debug APK ~112 MB with ML Kit text + biometric +
ZXing). Launched on-device with **no `FATAL EXCEPTION`**; the new UI renders:
- **Bottom navigation** (Inspect / Fleet / Settings, matte icons) — confirmed on screen.
- **Smart capture** plumbed: live `ImageAnalysis` (RGBA) → `LiveAnalyzer` (sharpness/exposure/
  vehicle-box/framing), live framing meter + bbox overlay + directional guidance, TTS `VoiceGuide`,
  haptics + capture tone, optional auto-capture. Settings toggles render (auto-capture, voice,
  haptics).
- **Number-plate OCR** (`PlateOcr`, ML Kit text) wired as "Scan plate" on the home screen +
  redaction path.
- **Review & evidence**: pinch-zoom viewer, before/after wipe slider, damage heatmap toggle;
  plate-redaction in exports (export copies only — originals + hashes untouched); verification QR of
  the manifest hash drawn on the PDF.
- **Supervisor analytics**: `FleetMetrics` (driver scorecards, damage rate, fleet KPIs) + Canvas
  bar/line charts on a new Analytics screen; vehicle/inspection search; periodic background sync;
  shift-reminder notifications (channel created at startup).
- **UX/access**: onboarding flow, empty/loading states, biometric (fingerprint) login
  (`MainActivity` is now `FragmentActivity`), quadratic-bezier signature smoothing, fuller ML/HI
  strings for the new surfaces.
- Settings screen confirmed rendering the new Model-registry / Smart-capture / Shift-reminders
  sections with working toggles (screenshot evidence).

Deeper live verification of individual heads (live framing reacting to a real vehicle, TTS audio,
plate OCR resolving a plate, analytics charts populated) is partial on this device/connection — same
constraints as Phase 2; the infrastructure is confirmed loaded and crash-free.

## ⏳ Remaining (code-complete; guided on-device verify)

These flows are implemented and code-reviewed; they need a human in the loop on this device
(blind adb automation of the Compose UI + signature draw was unreliable against the phone's
notification-shade gestures and an intermittent USB link). Each is verified by inspecting the DB /
pulled files after the action:

- Sign → finalize → hash-chain → verify VALID, then tamper a photo → BROKEN.
- Diff vs previous walkaround (NEW / PRE_EXISTING / RESOLVED + attribution) on a 2nd finalized run.
- Cleanliness baseline enrollment (low-confidence → relative).
- Dataset export ZIP + `findings.jsonl` schema.
- QR decode; model-active (detector loads in the gate) + graceful revert on a bad model.

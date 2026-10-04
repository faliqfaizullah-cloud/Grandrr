# Grandrr

A motion-reactive camera for Android. Move in front of the lens (or touch the screen) and the effect responds —
with soft generated notes that follow your movement.

**Effects:** Ember · Trace (Spring / Wake / Long exposure) · Silk · Fireflies · Vortex · Tesla · Resonance · Nebula · Opal ·
Dapple (Palm / Leaves / Fern / Branch) · Strings (Harp / Curtain / Reeds / Blinds)

- Swipe (or tap left/right) on the effect names to switch; pills change style; shuffle picks one at random
- White dot = photo (effect baked in) · Red = record video (effect included) · Green speaker = sound on/off
- Gallery button imports a photo; touch it to stir the effect
- Front/back camera, haptics, settings
- Sound: piano, glockenspiel, pads, plucked strings and electric crackle (Tesla), with a light reverb

## Build
Push to GitHub, then tag a release — Actions builds and attaches `Grandrr.apk`:

```
git tag v1.0.0 && git push origin main --tags
```

Local: `gradle assembleRelease` (JDK 17, Android SDK 34).

## Known limits
- Video is recorded with the effect baked in (camera + effect drawn into the encoder each frame, 720p, AAC audio)
- "Person only" button is a placeholder (needs a segmentation model)
- Imported photos ignore EXIF rotation

MIT License

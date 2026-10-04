package com.grandrr.camera

enum class Timbre { PIANO, BELL, PAD, SPARK, PLUCK, WARM }

enum class Effect(
    val label: String,
    val styles: List<String>,
    val defStyle: Int,
    val timbre: Timbre?,
    val line1: String,
    val line2: String,
    val music: Boolean = true
) {
    EMBER("EMBER", emptyList(), 0, Timbre.WARM, "", "Fan the embers to slow, warm piano"),
    TRACE("TRACE", listOf("Spring", "Wake", "Long exposure"), 1, Timbre.PIANO,
        "Effect and sound react to person only", "Push the dots to soft piano and birdsong"),
    SILK("SILK", emptyList(), 0, Timbre.PAD, "", "Pull the silk through slow strings"),
    FIREFLIES("FIREFLIES", emptyList(), 0, Timbre.BELL, "",
        "A wave wakes the lights alongside scattered piano and occasional glockenspiel. Pause to leave lights drifting and echoes ringing."),
    VORTEX("VORTEX", emptyList(), 0, Timbre.PAD, "", "Spin the air into a swirl of low pads"),
    TESLA("TESLA", emptyList(), 0, Timbre.SPARK, "", "Move to charge the air. Pull electricity with your hands.", false),
    RESONANCE("RESONANCE", emptyList(), 0, Timbre.BELL, "", "Resonance · Glow lattice"),
    NEBULA("NEBULA", emptyList(), 0, Timbre.PAD, "", "Stir clouds of colour to airy chimes"),
    OPAL("OPAL", emptyList(), 0, Timbre.BELL, "", "Tilt through opal light"),
    DAPPLE("DAPPLE", listOf("Palm", "Leaves", "Fern", "Branch"), 1, Timbre.PIANO,
        "", "Stir the %s to slowing piano runs"),
    STRINGS("STRINGS", listOf("Harp", "Curtain", "Reeds", "Blinds"), 3, Timbre.PLUCK,
        "", "Ripple the strings to piano and birdsong");

    val sound: Boolean get() = timbre != null
}

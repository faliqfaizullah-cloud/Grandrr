package com.grandrr.camera

enum class Effect(
    val label: String,
    val styles: List<String>,
    val defStyle: Int,
    val sound: Boolean,
    val line1: String,
    val line2: String
) {
    EMBER("EMBER", emptyList(), 0, true, "", "Fan the embers to slow, warm piano"),
    TRACE("TRACE", listOf("Spring", "Wake", "Long exposure"), 1, true,
        "Effect and sound react to person only", "Push the dots to soft piano and birdsong"),
    SILK("SILK", emptyList(), 0, true, "", "Pull the silk through slow strings"),
    FIREFLIES("FIREFLIES", emptyList(), 0, true, "",
        "A wave wakes the lights alongside scattered piano and occasional glockenspiel. Pause to leave lights drifting and echoes ringing."),
    VORTEX("VORTEX", emptyList(), 0, true, "", "Spin the air into a swirl of low pads"),
    TESLA("TESLA", emptyList(), 0, false, "", "Move to charge the air. Pull electricity with your hands."),
    RESONANCE("RESONANCE", emptyList(), 0, true, "", "Resonance · Glow lattice"),
    NEBULA("NEBULA", emptyList(), 0, true, "", "Stir clouds of colour to airy chimes"),
    OPAL("OPAL", emptyList(), 0, true, "", "Tilt through opal light"),
    DAPPLE("DAPPLE", listOf("Palm", "Leaves", "Fern", "Branch"), 1, true,
        "", "Stir the %s to slowing piano runs"),
    STRINGS("STRINGS", listOf("Harp", "Curtain", "Reeds", "Blinds"), 3, true,
        "", "Ripple the strings to piano and birdsong");
}

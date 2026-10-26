package cz.metlicka.m4control.device

import cz.metlicka.m4control.gaia.Cmd
import cz.metlicka.m4control.gaia.Codec
import cz.metlicka.m4control.device.NoiseControl.Confirmed
import cz.metlicka.m4control.device.NoiseControl.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseControlTest {
    @Test fun levelTransparencyRoundTrip() {
        for (l in 0..100) assertEquals(l, NoiseControl.levelFor(NoiseControl.transparencyFor(l)))
        assertEquals(0, NoiseControl.transparencyFor(100))
        assertEquals(50, NoiseControl.transparencyFor(50))
    }

    @Test fun stopsSnapTowardCenterOnTies() {
        assertEquals(3, NoiseControl.nearestStop(50))
        assertEquals(1, NoiseControl.nearestStop(85))
        // 58.5 isn't reachable; 58 is closer to 50 than to 67.
        assertEquals(3, NoiseControl.nearestStop(58))
        // 75 is equidistant from 83 and 67 -> 67 (closer to center).
        assertEquals(2, NoiseControl.nearestStop(75))
        assertEquals(4, NoiseControl.nearestStop(25))
    }

    @Test fun readouts() {
        assertEquals("Noise Cancelling 66%", NoiseControl.readout(NoiseMode.CUSTOM, 83).text)
        assertEquals("Transp. 34%", NoiseControl.readout(NoiseMode.CUSTOM, 33).short)
        assertEquals("Balanced", NoiseControl.readout(NoiseMode.CUSTOM, 50).text)
        assertEquals("Adaptive", NoiseControl.readout(NoiseMode.ADAPTIVE, 83).text)
        assertEquals("Off", NoiseControl.readout(NoiseMode.OFF, 83).text)
    }

    @Test fun customFromAdaptiveSendsAllSteps() {
        val s = NoiseControl.steps(Target.Custom(83), Confirmed(true, true, 50))
        assertEquals(listOf(Step(Cmd.ANC_MODE_SET, 3, 0), Step(Cmd.TRANSPARENCY_SET, 17)), s)
    }

    @Test fun customFromOffEnablesMasterFirst() {
        val s = NoiseControl.steps(Target.Custom(50), Confirmed(false, false, 50))
        assertEquals(listOf(Step(Cmd.MASTER_SET, 1)), s)
    }

    @Test fun unconfirmedStepsAreNotSkipped() {
        val s = NoiseControl.steps(Target.Custom(100), Confirmed(null, null, null))
        assertEquals(listOf(Step(Cmd.MASTER_SET, 1), Step(Cmd.ANC_MODE_SET, 3, 0), Step(Cmd.TRANSPARENCY_SET, 0)), s)
    }

    @Test fun adaptiveAndOff() {
        assertEquals(listOf(Step(Cmd.ANC_MODE_SET, 3, 1)), NoiseControl.steps(Target.Adaptive, Confirmed(true, false, 10)))
        assertEquals(listOf(Step(Cmd.MASTER_SET, 0)), NoiseControl.steps(Target.Off, Confirmed(true, true, 10)))
        assertEquals(emptyList<Step>(), NoiseControl.steps(Target.Off, Confirmed(false, true, 10)))
    }

    @Test fun modeFromState() {
        assertEquals(NoiseMode.OFF, NoiseControl.mode(false, true))
        assertEquals(NoiseMode.ADAPTIVE, NoiseControl.mode(true, true))
        assertEquals(NoiseMode.CUSTOM, NoiseControl.mode(true, false))
        assertNull(NoiseControl.mode(true, null))
    }
}

class EqModelTest {
    private val presets = listOf(
        Preset("warm", "Warm", listOf(30, 15, 0, -10, -20), true),
        Preset("bright", "Bright", listOf(-10, 0, 5, 20, 30), true),
        Preset("vocal", "Vocal", listOf(-20, -5, 25, 20, 0), true),
        Preset("deepBass", "Deep Bass", listOf(50, 25, 0, 0, 0), true),
        Preset("soft", "Soft", listOf(10, 5, 0, -15, -30), true),
        Preset("user1", "Train", listOf(40, 15, 0, -10, -20), false),
    )
    private val cfg = Codec.EqConfig(5, -60, 60, 0, 0)

    @Test fun labels() {
        assertEquals("Warm", EqModel.label(1, listOf(30, 15, 0, -10, -20), presets))
        assertEquals("Flat EQ", EqModel.label(1, listOf(0, 0, 0, 0, 0), presets))
        assertEquals("Custom", EqModel.label(1, listOf(5, 0, 0, 0, 0), presets))
        assertEquals("Podcast", EqModel.label(2, listOf(30, 15, 0, -10, -20), presets))
        assertEquals("Bypass", EqModel.label(0, null, presets))
        assertEquals("Unknown mode (7)", EqModel.label(7, null, presets))
    }

    @Test fun editorTitleNamesThePresetBeingEdited() {
        val edited = listOf(30, 15, 0, -10, -15)
        assertEquals("Warm · edited", EqModel.editorTitle(1, edited, presets, "warm"))
        assertEquals("Custom", EqModel.editorTitle(1, edited, presets, null))
        assertEquals("Warm", EqModel.editorTitle(1, presets[0].gains, presets, "bright"))
        assertEquals("Flat EQ", EqModel.editorTitle(1, listOf(0, 0, 0, 0, 0), presets, "warm"))
        assertEquals("Podcast", EqModel.editorTitle(2, edited, presets, "warm"))
        assertEquals("Custom", EqModel.editorTitle(1, edited, presets, "deleted-id"))
    }

    @Test fun validationRefusesInsteadOfClamping() {
        assertNull(EqModel.validate(presets[0].gains, cfg))
        assertNotNull(EqModel.validate(listOf(0, 0, 0, 0, 65), cfg))
        assertNotNull(EqModel.validate(listOf(0, 0, 0, 0), cfg))
        assertNotNull(EqModel.validate(presets[0].gains, null))
    }

    @Test fun applyOnlyChangedBandsThenUserEq() {
        // Vocal over Warm: every band changes; negative gains go out as signed bytes (-20 = 0xEC).
        val s = EqModel.applySteps(presets[2].gains, presets[0].gains, Cmd.AUDIO_PODCAST)
        assertEquals(
            listOf(Step(Cmd.EQ_GAIN_SET, 0, 0xEC), Step(Cmd.EQ_GAIN_SET, 1, 0xFB), Step(Cmd.EQ_GAIN_SET, 2, 25),
                Step(Cmd.EQ_GAIN_SET, 3, 20), Step(Cmd.EQ_GAIN_SET, 4, 0), Step(Cmd.AUDIO_MODE_SET, 0, 1)),
            s,
        )
        assertEquals(listOf(Step(Cmd.EQ_GAIN_SET, 0, 40)),
            EqModel.applySteps(presets[5].gains, presets[0].gains, Cmd.AUDIO_USER_EQ))
        assertEquals(emptyList<Step>(), EqModel.applySteps(presets[0].gains, presets[0].gains, Cmd.AUDIO_USER_EQ))
    }

    @Test fun bassMaskPreservesOtherBits() {
        assertEquals(0x07, EqModel.bassMask(0x06, true))
        assertEquals(0x06, EqModel.bassMask(0x07, false))
        assertEquals(0x00, EqModel.bassMask(0x01, false))
    }

    @Test fun nameRules() {
        assertEquals("Enter a name", EqModel.nameError("   ", null, presets))
        assertEquals("A preset with this name already exists", EqModel.nameError(" warm ", null, presets))
        assertNull(EqModel.nameError("Train", "user1", presets)) // renaming to its own name
        assertNotNull(EqModel.nameError("x".repeat(31), null, presets))
        assertNull(EqModel.nameError("Commute", null, presets))
    }

    @Test fun favorites() {
        val favs = listOf("warm", "bright", "vocal", "deepBass")
        assertNotNull(EqModel.toggleFavorite(favs, "soft").error)
        assertEquals(listOf("warm", "bright", "vocal"), EqModel.toggleFavorite(favs, "deepBass").favorites)
        assertNotNull(EqModel.toggleFavorite(listOf("warm"), "warm").error)
    }

    @Test fun deletingAFavoriteRefillsFromBuiltIns() {
        val favs = listOf("warm", "bright", "vocal", "user1")
        val r = EqModel.favoritesAfterDelete(favs, "user1", presets.filter { it.id != "user1" })
        assertEquals(listOf("warm", "bright", "vocal", "deepBass"), r.favorites)
        assertEquals("deepBass", r.refill)
        assertEquals(favs, EqModel.favoritesAfterDelete(favs, "soft", presets).favorites)
    }
}

class PresetOrderTest {
    private fun p(id: String, builtIn: Boolean = true) = Preset(id, id, listOf(0, 0, 0, 0, 0), builtIn)
    private val list = listOf(p("warm"), p("bright"), p("vocal"), p("soft"), p("user1", false))

    @Test fun userOrderWinsAndUnknownIdsStayAtTheEnd() {
        val o = EqModel.ordered(list + p("user2", false), listOf("soft", "user1", "warm", "gone"))
        assertEquals(listOf("soft", "user1", "warm", "bright", "vocal", "user2"), o.map { it.id })
    }

    @Test fun noOrderKeepsDefault() = assertEquals(list, EqModel.ordered(list, emptyList()))

    @Test fun favoritesFollowListOrder() {
        val o = EqModel.ordered(list, listOf("soft", "bright", "warm"))
        assertEquals(listOf("soft", "bright", "warm"), EqModel.favoritePresets(o, listOf("warm", "bright", "soft")).map { it.id })
    }
}

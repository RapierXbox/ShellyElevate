package me.rapierxbox.shellyelevatev2.display

import me.rapierxbox.shellyelevatev2.display.options.ModuleOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModuleRegistryTest {

    @Test
    fun moduleIdsAreUnique() {
        val ids = DisplayModuleRegistry.modules.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(DisplayModuleRegistry.byId(DisplayModuleRegistry.DEFAULT_ID))
    }

    // every module shares one prefs file so keys must never collide
    @Test
    fun optionKeysAreUniqueAcrossModules() {
        val keys = DisplayModuleRegistry.modules.flatMap { module ->
            module.options.flatMap { option ->
                if (option is ModuleOption.AppPicker) listOf(option.key, option.componentKey) else listOf(option.key)
            }
        }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun visibleWhenPointsAtAnOptionOfTheSameModule() {
        for (module in DisplayModuleRegistry.modules) {
            val keys = module.options.map { it.key }.toSet()
            for (option in module.options) {
                val rule = option.visibleWhen ?: continue
                assertTrue("${module.id}.${option.key} depends on unknown ${rule.key}", rule.key in keys)
            }
        }
    }
}

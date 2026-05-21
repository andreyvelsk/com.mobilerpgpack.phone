package com.mobilerpgpack.phone.engine

import com.mobilerpgpack.phone.BuildConfig

enum class EngineTypes {
    WolfensteinRpg,
    DoomRpg,
    Doom2Rpg,
    Doom64ExPlus,
    Doom64ExPlusEnhanced,
    PsyDoom,
    UZDoom,
    PerfectDark,
    ArxLibertatis,
    FTEQW,
    Widelands,
    VanillaConquer,
    Classic_RBDOOM_3_BFG;

    companion object {
        val DefaultActiveEngine = UZDoom

        // Controlled by ENGINES_TO_BUILD in app/build.gradle (single source of truth).
        val ENABLED_ENGINES: List<EngineTypes> = BuildConfig.ENGINES_TO_BUILD
            .split(",")
            .mapNotNull { name -> entries.find { it.name == name } }
    }
}
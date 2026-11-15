package com.rw3.directsync.data

import com.rw3.directsync.R

/** Canonical mapping between RW3/Mi Fitness workout types, UI icons, and filter tabs. */
object SportTypeCatalog {

    enum class Filter { ALL, RUN, WALK, BIKE, GYM }

    private val runTypes = setOf(1, 3, 4)
    private val walkTypes = setOf(2, 5, 15)
    private val bikeTypes = setOf(6, 7)

    fun displayName(sportType: Int?): String = when (sportType) {
        null -> "Olahraga"
        0 -> "Olahraga"
        1 -> "Lari Luar Ruangan"
        2, 15 -> "Jalan Kaki"
        3 -> "Lari Dalam Ruangan"
        4 -> "Lari"
        5 -> "Pendakian"
        6 -> "Bersepeda Luar"
        7 -> "Sepeda Statis"
        8 -> "Latihan Bebas"
        12 -> "Yoga"
        16 -> "HIIT"
        19 -> "Berenang"
        20 -> "Sepak Bola"
        21 -> "Eliptikal"
        22 -> "Dayung"
        305 -> "Pilates"
        308 -> "Latihan Kekuatan"
        608 -> "Bulu Tangkis"
        else -> "Olahraga #$sportType"
    }

    fun iconRes(sportType: Int?): Int = when (sportType) {
        in runTypes -> R.drawable.ic_directions_run
        in walkTypes -> R.drawable.ic_nordic_walking
        in bikeTypes -> R.drawable.ic_pedal_bike
        else -> R.drawable.ic_fitness_center
    }

    fun matchesFilter(sportType: Int?, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.RUN -> sportType in runTypes
        Filter.WALK -> sportType in walkTypes
        Filter.BIKE -> sportType in bikeTypes
        Filter.GYM -> sportType !in runTypes && sportType !in walkTypes && sportType !in bikeTypes
    }
}

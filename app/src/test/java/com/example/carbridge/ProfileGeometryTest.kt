package com.example.carbridge

import org.junit.Assert.*
import org.junit.Test

class ProfileGeometryTest {
    @Test fun dockNeverConsumesMoreThanHostAllowsOnSmallOrDenseHeadUnits() {
        for (height in listOf(240, 480, 720, 768, 1080)) for (density in listOf(.5f, 1f, 1.25f, 2f, 3f)) {
            val reserve = ProfileGeometry.dockHeight(height, density)
            assertTrue("dock at $height / $density", reserve > 0 && reserve <= height / 5)
            assertTrue("host 96dp limit", reserve <= 96 * density)
            assertTrue("app area stays usable", height - reserve >= height * .8)
        }
    }
    @Test fun homeAndMusicLayoutsKeepScrollableCatalogAtBothAspectRatios() {
        for (width in listOf(800, 1024, 1280, 1920)) for (density in listOf(1f, 1.25f, 2f)) {
            val full = ProfileGeometry.columns(width, density)
            val alongsideMusic = ProfileGeometry.columns((width * .7f).toInt(), density)
            assertTrue(full in 2..8)
            assertTrue(alongsideMusic in 2..full)
        }
    }
}

package com.kursat.reader

import com.kursat.reader.model.LanguageDetect
import com.kursat.reader.model.TargetLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectTest {
    @Test
    fun detectsEnglishAndTurkish() {
        val en = "It was a bright cold day in April, and the clocks were striking thirteen. Winston Smith slipped quickly through the glass doors."
        assertEquals(TargetLanguage.en, LanguageDetect.detect(en))
        val tr = "Nisan ayında soğuk ve aydınlık bir gündü, saatler on üçü vuruyordu. Winston Smith cam kapılardan hızla içeri girdi."
        assertEquals(TargetLanguage.tr, LanguageDetect.detect(tr))
    }
}

package com.example.autoclick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReglasGuionTest {
    @Test fun idDeReel() = assertEquals("ABC_123", ReglasGuion.idPublicacion("https://www.instagram.com/reel/ABC_123/?igsh=x"))
    @Test fun idDePublicacion() = assertEquals("XyZ", ReglasGuion.idPublicacion("https://instagram.com/p/XyZ/"))
    @Test fun sinIdSiNoEsPublicacion() = assertNull(ReglasGuion.idPublicacion("https://instagram.com/perfil/"))
    @Test fun describeSinMenciones() = assertEquals("sin menciones (solo frase)", ReglasGuion.describirMenciones(0, false))
    @Test fun describeUnaMencion() = assertEquals("1 mención con frase", ReglasGuion.describirMenciones(1, true))
    @Test fun describeVarias() = assertEquals("3 menciones sin frase", ReglasGuion.describirMenciones(3, false))
}

package com.estrella.sorteos

import android.app.Activity

/** En la versión normal (APK) no hay pagos de Google: se usan los códigos de activación (Licencia). */
object Pagos {
    const val enabled = false
    fun start(act: Activity, onChange: () -> Unit) {}
    fun planJson(): String? = null
    fun refresh() {}
    fun productsJson(): String = "{}"
    fun buy(act: Activity, plan: String, period: String) {}
    fun manage(act: Activity) {}
    fun end() {}
}

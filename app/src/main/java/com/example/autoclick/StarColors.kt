package com.example.autoclick

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Pinta las pantallas de Star App con colores (botones de colores, títulos morados, cuadros redondeados). */
object StarColors {
    private val PALETTE = listOf(
        intArrayOf(0xFF8B5CF6.toInt(), 0xFF6A3DE8.toInt()), // morado
        intArrayOf(0xFFFF5FA2.toInt(), 0xFFE0307A.toInt()), // rosa
        intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt()), // azul
        intArrayOf(0xFFFFB020.toInt(), 0xFFF57C00.toInt()), // naranja
        intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt()), // verde
        intArrayOf(0xFF2DD4BF.toInt(), 0xFF0D9488.toInt())  // turquesa
    )
    private val GREEN = intArrayOf(0xFF4ADE80.toInt(), 0xFF16A34A.toInt())
    private val RED = intArrayOf(0xFFFF6B6B.toInt(), 0xFFDC2626.toInt())
    private const val TITLE = 0xFF6A3DE8.toInt()
    private const val TEXT = 0xFF2D1B69.toInt()
    /** Vistas ya pintadas (se olvidan solas cuando la pantalla se cierra). */
    private val painted: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    private fun gradient(c: IntArray, radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, c
    ).apply { cornerRadius = radius }

    private fun colorsFor(text: String, index: Int): IntArray {
        val t = text.trim().lowercase()
        return when {
            t.startsWith("▶") || t.contains("iniciar") -> GREEN
            t.startsWith("■") || t.contains("borrar") || t.contains("parar") || t.contains("detener") || t.contains("quitar") -> RED
            else -> PALETTE[index % PALETTE.size]
        }
    }

    /** Pinta la pantalla ahora y también lo que se añada después (listas que se rellenan solas). */
    fun watch(root: View) {
        paint(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { paint(root) }
    }

    fun paint(root: View) {
        var index = 0
        fun walk(v: View) {
            when (v) {
                is Button -> {
                    if (painted.add(v)) {
                        val d = v.resources.displayMetrics.density
                        v.background = gradient(colorsFor(v.text.toString(), index), 16 * d)
                        v.setTextColor(Color.WHITE)
                        v.setTypeface(v.typeface, Typeface.BOLD)
                        v.isAllCaps = false
                        v.elevation = 3 * d
                        v.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                        (v.layoutParams as? LinearLayout.LayoutParams)?.let {
                            if (it.topMargin == 0 && it.bottomMargin == 0) {
                                it.topMargin = (5 * d).toInt(); it.bottomMargin = (5 * d).toInt()
                                v.layoutParams = it
                            }
                        }
                    }
                    index++
                }
                is EditText -> {
                    if (painted.add(v)) {
                        val d = v.resources.displayMetrics.density
                        v.background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = 12 * d
                            setStroke((1.5f * d).toInt(), 0xFFB79CFF.toInt())
                        }
                        v.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                        v.setTextColor(TEXT)
                    }
                }
                is TextView -> {
                    if (painted.add(v)) {
                        val t = v.text.toString().trim()
                        val letters = t.filter { it.isLetter() }.take(6)
                        val isTitle = v.textSize / v.resources.displayMetrics.scaledDensity >= 17.5f ||
                            (letters.length >= 4 && letters == letters.uppercase())
                        if (isTitle) {
                            v.setTextColor(TITLE)
                            v.setTypeface(v.typeface, Typeface.BOLD)
                        } else {
                            v.setTextColor(TEXT)
                        }
                    }
                }
                is Spinner -> {
                    if (painted.add(v)) {
                        val d = v.resources.displayMetrics.density
                        v.background = GradientDrawable().apply {
                            setColor(0xFFF1EAFF.toInt())
                            cornerRadius = 12 * d
                            setStroke((1.5f * d).toInt(), 0xFFB79CFF.toInt())
                        }
                    }
                }
            }
            if (v is ViewGroup && v !is Spinner) for (k in 0 until v.childCount) walk(v.getChildAt(k))
        }
        walk(root)
    }

    /** Botón flotante: verde con ▶ (parado) y rojo con ■ (en marcha). */
    fun overlay(b: Button) {
        val d = b.resources.displayMetrics.density
        val running = b.text.toString().trim().startsWith("■")
        b.background = gradient(if (running) RED else GREEN, 18 * d)
        b.setTextColor(Color.WHITE)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.isAllCaps = false
        b.elevation = 6 * d
    }
}

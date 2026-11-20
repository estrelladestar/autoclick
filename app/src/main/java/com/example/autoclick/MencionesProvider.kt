package com.example.autoclick

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Deja que «Star App Sencilla» lea las menciones de esta app: las fijas (si están activadas) o las del Etiquetador.
 * Solo puede leerlas una app firmada con la misma clave que pida el permiso (ver AndroidManifest.xml).
 */
class MencionesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("texto"))
        val ctx = context ?: return cursor
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val fijas = p.getString("menciones_fijas", "") ?: ""
        val texto = if (p.getBoolean("menciones_fijas_on", true) && fijas.isNotBlank()) fijas else (p.getString("friends", "") ?: "")
        cursor.addRow(arrayOf<Any>(texto))
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

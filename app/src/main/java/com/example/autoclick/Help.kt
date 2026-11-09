package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView

/** Ventana con las instrucciones de uso de la app. */
object Help {
    private const val TEXT = """CÓMO SE USA

PREPARACIÓN (solo la primera vez)
1. En la pantalla principal pulsa «1. Activar servicio de accesibilidad», busca AutoClick Imagen y actívalo.
2. Si tu Android es anterior al 11, pulsa también «Permitir captura de pantalla» (hay que repetirlo cada vez que abras la app).
3. Pulsa «+ Añadir imagen» y elige una captura del botón que quieres pulsar (por ejemplo «Publicar»). Es la imagen 1. Para cambiarla, bórrala con ✕ y añade otra.
4. Pon tus enlaces en los cuadros «Enlace 1…5» (hasta 5).
5. Pulsa «Guardar y mostrar botón flotante».

MENCIONES
1. Abre «Abrir el Etiquetador de sorteos» y escribe tus amigos en el cuadro «Menciones» (uno por línea).
2. Se copian solos al guion. También puedes escribirlos en «TUS MENCIONES» dentro de «Comentarios automáticos (guion)».

COMENTARIOS AUTOMÁTICOS
1. Entra en «Comentarios automáticos (guion)».
2. Pulsa «Restaurar guion de ejemplo».
3. Elige cuántas menciones por comentario (1 a 5).
4. Pulsa «Iniciar». La app se esconde y empieza sola.
5. Para parar, toca ■ en el botón flotante.

QUÉ HACE EL GUION DE EJEMPLO
enlace → abre el siguiente enlace
esperar 8 → espera a que cargue
campo → abre la caja de comentarios
escribir menciones → escribe @amigos al azar
imagen 1 → pulsa tu imagen (Publicar)
| 30 → espera 30 s después
repetir 10 → lo hace 10 veces

OTROS PASOS
tocar Texto → toca el botón que diga así
tocar 500,1200 → toca esa posición
escribir azar → comentario al azar de tu lista
escribir lista → comentarios de tu lista en orden
escribir Hola → texto fijo
imagen 2 → usa la segunda imagen subida
# → nota, se ignora

CONSEJOS
• Haz primero una prueba con «repetir 1».
• Si dice que no encuentra algo, revisa el texto del botón o usa la imagen.
• Máximo 50 comentarios por tanda y 10 s entre uno y otro.
• Instagram y TikTok pueden limitar cuentas que comentan mucho seguido: ve despacio."""

    fun show(a: Activity) {
        val tv = TextView(a).apply {
            text = TEXT
            textSize = 15f
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(a)
            .setTitle("Cómo se usa")
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("Entendido", null)
            .show()
    }
}

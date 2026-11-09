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
3. Los comentarios que genera el Etiquetador en su lista también pasan solos a «TUS COMENTARIOS» (no hace falta copiarlos). Si la lista está vacía no se borra lo que ya tengas allí.

COMENTARIOS AUTOMÁTICOS
1. Entra en «Comentarios automáticos (guion)».
2. Pulsa «Restaurar guion de ejemplo».
3. Elige cuántas menciones por comentario (1 a 5).
4. Si quieres, escribe tus frases en «TUS FRASES» (una por línea), elige si van delante o detrás y con qué probabilidad. Se juntan con las menciones.
5. Pulsa «Iniciar». La app se esconde y empieza sola.
6. Para parar, toca ■ en el botón flotante. El botón muestra cuántos comentarios lleva (por ejemplo ■ 7/50).

ASISTENTE (decirle lo que quieres)
1. En «Comentarios automáticos (guion)», arriba, escribe o pulsa 🎤 y di, por ejemplo: «abre el enlace, pulsa la imagen 1, luego la imagen 2, escribe un comentario copiado con 2 menciones, pulsa otra imagen y repite 5 veces».
2. Pulsa «Crear guion». Te lo enseña: si está bien, pulsa «Usar este guion» y luego «Iniciar». Si te falta algo, corrígelo a mano en esa misma ventana, o cambia tu frase (se queda guardada; con 🎤 se añade al final) y vuelve a pulsar «Crear guion».
3. Sin clave API funciona sin IA y sin internet: entiende frases sencillas, una acción detrás de otra, y si no entiende una parte te dice cuál. Con tu clave API de Anthropic (cuadro de abajo) usa una IA que entiende frases libres; necesita internet, se paga aparte (muy poco por frase) y solo envía tu frase, nunca tus comentarios, menciones ni enlaces. Si la IA falla, vuelve al modo sin IA. Revisa siempre el guion antes de usarlo. (El botón 🎤 usa el reconocimiento de voz de tu móvil, que según el móvil puede necesitar internet.)
4. «otra imagen» usa la siguiente a la última que dijiste (imagen 3 después de la 2). Sube las imágenes en orden en la pantalla principal.
5. «comentario copiado con 2 menciones» saca un comentario al azar de «TUS COMENTARIOS» y le añade 2 menciones.
6. «vuelve a pulsar» (o «de nuevo») repite el último toque. «pulsa el icono del perfil de abajo a la derecha» toca esa zona de la pantalla.
7. «cambia a otra cuenta» mantiene pulsado el perfil para abrir la lista de cuentas; para elegir una di su nombre («cambia a la cuenta NOMBRE») o sube una captura suya y di «pulsa la imagen N».

GUIONES GUARDADOS
Con «Guardar como…» guardas el guion con un nombre (por ejemplo Instagram o TikTok). Elígelo en la lista para cargarlo, y «Borrar este» lo elimina.

QUÉ HACE EL GUION DE EJEMPLO
enlace → abre el siguiente enlace
esperar 8 → espera a que cargue
campo → abre la caja de comentarios
escribir menciones → escribe @amigos al azar
imagen 1 → pulsa tu imagen (Publicar)
| 30-60 → espera entre 30 y 60 s al azar (| 30 = siempre 30)
repetir 10 → lo hace 10 veces

OTROS PASOS
tocar Texto → toca el botón que diga así
tocar 500,1200 → toca esa posición
tocar 90%,95% → toca abajo a la derecha (porcentajes de la pantalla; 10%,95% = abajo a la izquierda)
mantener 90%,95% → pulsación larga (en Instagram, sobre el perfil abre la lista de cuentas)
escribir azar → comentario al azar de tu lista
escribir copiados menciones 2 → comentario copiado al azar + 2 menciones
escribir lista → comentarios de tu lista en orden
escribir Hola → texto fijo
imagen 2 → usa la segunda imagen subida
esperar 5-10 → espera entre 5 y 10 s al azar
# → nota, se ignora

CONSEJOS
• Haz primero una prueba con «repetir 1».
• Si dice que no encuentra algo, revisa el texto del botón o usa la imagen.
• Máximo 50 comentarios por tanda y 10 s entre uno y otro.
• Si aparece un aviso de bloqueo de Instagram, el guion se para solo y el móvil vibra. Espera unas horas antes de volver a probar.
• Tras instalar una versión nueva, mira el número de versión arriba en la pantalla principal para saber que es la última.
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

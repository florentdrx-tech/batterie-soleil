package be.florent.soleil

import java.util.Locale

/** Langue de l'appli : néerlandais si le téléphone (ou l'appli) est en néerlandais, sinon français. */
object L {
    val nl: Boolean get() = Locale.getDefault().language == "nl"
    val loc: Locale get() = if (nl) Locale("nl", "BE") else Locale.FRENCH
    fun t(fr: String, nl: String): String = if (this.nl) nl else fr
}

package co.com.synthax.bet.enums;

public enum CategoriaAnalisis {
    RESULTADO,        // 1X2, doble oportunidad
    GOLES,            // over/under total, BTTS, goles por equipo
    MARCADOR_EXACTO,  // marcador exacto, clean sheet, win to nil
    HANDICAP,         // hándicap asiático, hándicap europeo
    CORNERS,          // total corners, handicap corners
    CORNERS_EQUIPO,   // corners por equipo individual (local / visitante)
    TARJETAS,         // amarillas, rojas, totales
    TIROS,            // tiros al arco, al poste
    FALTAS,           // faltas totales, por equipo
    JUGADOR           // anotador, tarjeta, tiros
}

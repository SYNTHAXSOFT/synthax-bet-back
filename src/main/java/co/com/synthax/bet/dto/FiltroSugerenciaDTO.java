package co.com.synthax.bet.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Filtros enviados por el usuario desde la pantalla "Personalizar Sugerencias".
 * Todos los campos son opcionales — si viene null se usa el valor por defecto.
 */
@Data
@NoArgsConstructor
public class FiltroSugerenciaDTO {

    /**
     * Probabilidad mínima de cada selección individual (0.0 - 1.0).
     * Por defecto 0.50 (50 %).
     */
    private Double probMinima;

    /**
     * Probabilidad máxima de cada selección individual (0.0 - 1.0).
     * Por defecto 1.0 (sin límite superior).
     */
    private Double probMaxima;

    /**
     * Cuota mínima de la combinada resultante.
     * Por defecto 1.80.
     */
    private Double cuotaMinimaTotal;

    /**
     * Texto libre para filtrar por equipo.
     * Si se indica, al menos una selección de la combinada debe pertenecer
     * a un partido donde juegue ese equipo (búsqueda case-insensitive).
     */
    private String equipoBuscado;

    /**
     * Liga por la que filtrar (ej: "La Liga", "Premier League", "Champions League").
     * Si se indica, TODAS las selecciones de la combinada deben pertenecer a esa liga.
     * Búsqueda parcial case-insensitive.
     */
    private String ligaBuscada;

    /**
     * Tipo de apuesta deseado: "Simple", "Doble", "Triple" o null = todos.
     */
    private String tipoApuesta;

    /**
     * Categorías a incluir (GOLES, CORNERS, TARJETAS, RESULTADO, HANDICAP, MARCADOR_EXACTO).
     * Si viene vacío o null se incluyen todas las apostables.
     */
    private java.util.List<String> categorias;
}

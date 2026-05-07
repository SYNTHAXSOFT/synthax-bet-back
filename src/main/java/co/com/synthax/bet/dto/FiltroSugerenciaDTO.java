package co.com.synthax.bet.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Filtros enviados por el usuario desde la pantalla "Personalizar Sugerencias".
 * Todos los campos son opcionales — si vienen null o vacíos se usan valores por defecto.
 */
@Data
@NoArgsConstructor
public class FiltroSugerenciaDTO {

    /** Probabilidad mínima de cada selección individual (0.0 - 1.0). */
    private Double probMinima;

    /** Probabilidad máxima de cada selección individual (0.0 - 1.0). */
    private Double probMaxima;

    /** Cuota mínima de la combinada resultante. */
    private Double cuotaMinimaTotal;

    /**
     * Equipos a buscar (multi-selección).
     * Si se indica 1 equipo → todas las patas son de ese partido.
     * Si se indican 2+ equipos → cada equipo aporta patas a la combinada.
     */
    private List<String> equiposBuscados;

    /**
     * Ligas a filtrar (multi-selección).
     * Solo aparecen partidos de alguna de estas ligas.
     */
    private List<String> ligasBuscadas;

    /** Tipo de apuesta deseado: "Simple", "Doble", "Triple" o null = todos. */
    private String tipoApuesta;

    /**
     * Cuota mínima por pata individual.
     * Solo se incluyen en el pool picks con cuota >= este valor.
     * Si es null no se aplica filtro adicional (usa el mínimo interno del sistema).
     */
    private Double cuotaMinimaPorPata;

    /**
     * Categorías a incluir (GOLES, CORNERS, TARJETAS, RESULTADO, HANDICAP, MARCADOR_EXACTO).
     * Si viene vacío o null se incluyen todas las apostables.
     */
    private List<String> categorias;
}

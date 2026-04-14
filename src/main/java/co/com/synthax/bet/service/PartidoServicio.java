package co.com.synthax.bet.service;

import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.EstadoPartido;
import co.com.synthax.bet.proveedor.ProveedorFutbol;
import co.com.synthax.bet.proveedor.modelo.PartidoExterno;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import co.com.synthax.bet.repository.CuotaRepositorio;
import co.com.synthax.bet.repository.PartidoRepositorio;
import co.com.synthax.bet.repository.PickRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartidoServicio {

    private final PartidoRepositorio  partidoRepositorio;
    private final CuotaRepositorio    cuotaRepositorio;
    private final AnalisisRepositorio analisisRepositorio;
    private final PickRepositorio     pickRepositorio;
    private final ProveedorFutbol     proveedorFutbol;

    /**
     * Obtiene los partidos del día de hoy.
     * Primero busca en BD; si no hay datos frescos, consulta la API y sincroniza.
     */
    public List<Partido> obtenerPartidosDeHoy() {
        LocalDate hoy = LocalDate.now();
        return obtenerPartidosPorFecha(hoy);
    }

    /**
     * Obtiene partidos de una fecha específica.
     * Sincroniza desde la API si la BD no tiene datos del día.
     */
    public List<Partido> obtenerPartidosPorFecha(LocalDate fecha) {
        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(23, 59, 59);

        List<Partido> enBd = partidoRepositorio.findByFechaPartidoBetween(inicioDia, finDia);

        if (!enBd.isEmpty()) {
            log.info(">>> {} partidos encontrados en BD para {}", enBd.size(), fecha);
            return enBd;
        }

        log.info(">>> Sin partidos en BD para {}. Sincronizando desde API...", fecha);
        return sincronizarPartidos(fecha);
    }

    /**
     * Fuerza sincronización con la API y persiste los partidos en BD.
     *
     * Antes de insertar datos frescos, elimina todos los partidos en una
     * ventana de ±30 horas alrededor de la fecha pedida. Esto resuelve el
     * problema de registros con timezone incorrecto (partidos de ayer
     * guardados con hora UTC aparecían como partidos de hoy).
     *
     * Ejemplo: sincronizar 2026-04-07
     *   → elimina partidos con fechaPartido BETWEEN 2026-04-06T18:00 AND 2026-04-08T06:00
     *   → inserta datos frescos desde API con timezone=America/Bogota
     *   → solo quedan partidos reales del día en hora Colombia
     */
    public List<Partido> sincronizarPartidos(LocalDate fecha) {

        // Limpiar registros en ventana ±6h para eliminar cualquier dato
        // con timezone incorrecto que haya quedado de sincronizaciones anteriores.
        // Orden obligatorio: cuotas → análisis → partidos (llaves foráneas).
        LocalDateTime desde = fecha.atTime(0, 0).minusHours(6);   // ayer 18:00
        LocalDateTime hasta = fecha.atTime(23, 59).plusHours(6);   // mañana 06:00

        List<Partido> aEliminar = partidoRepositorio.findByFechaPartidoBetween(desde, hasta);
        if (!aEliminar.isEmpty()) {
            List<Long> ids = aEliminar.stream().map(Partido::getId).toList();

            // 1. Eliminar picks que referencian esos partidos
            pickRepositorio.deleteByPartidoIdIn(ids);
            // 2. Eliminar cuotas que referencian esos partidos
            cuotaRepositorio.deleteByPartidoIdIn(ids);
            // 3. Eliminar análisis que referencian esos partidos
            analisisRepositorio.deleteByPartidoIdIn(ids);
            // 4. Eliminar los partidos (ahora sin hijos que los bloqueen)
            partidoRepositorio.deleteByFechaPartidoBetween(desde, hasta);

            log.info(">>> {} partidos eliminados (+ sus cuotas y análisis) antes de re-sincronizar", aEliminar.size());
        }

        List<PartidoExterno> externos = proveedorFutbol.obtenerPartidosDelDia(fecha);

        if (externos.isEmpty()) {
            log.warn(">>> La API no devolvió partidos para {}", fecha);
            return List.of();
        }

        List<Partido> guardados = externos.stream()
                .map(this::upsertarPartido)
                .toList();

        log.info(">>> {} partidos sincronizados para {} (timezone: America/Bogota)", guardados.size(), fecha);
        return guardados;
    }

    /**
     * Inserta o actualiza un partido según su idPartidoApi.
     */
    private Partido upsertarPartido(PartidoExterno externo) {
        Optional<Partido> existente = partidoRepositorio.findByIdPartidoApi(externo.getIdExterno());

        Partido partido = existente.orElse(new Partido());

        partido.setIdPartidoApi(externo.getIdExterno());
        partido.setEquipoLocal(externo.getEquipoLocal());
        partido.setEquipoVisitante(externo.getEquipoVisitante());
        partido.setIdEquipoLocalApi(externo.getIdEquipoLocal());
        partido.setIdEquipoVisitanteApi(externo.getIdEquipoVisitante());
        partido.setLiga(externo.getLiga());
        partido.setIdLigaApi(externo.getIdLiga());
        partido.setPais(externo.getPais());
        partido.setTemporada(externo.getTemporada());
        partido.setRonda(externo.getRonda());
        partido.setFechaPartido(externo.getFechaPartido());
        partido.setArbitro(externo.getArbitro());
        partido.setLogoLocal(externo.getLogoLocal());
        partido.setLogoVisitante(externo.getLogoVisitante());
        partido.setEstado(mapearEstado(externo.getEstado()));

        return partidoRepositorio.save(partido);
    }

    public Partido obtenerPorId(Long id) {
        return partidoRepositorio.findById(id)
                .orElseThrow(() -> new RuntimeException("Partido no encontrado con id: " + id));
    }

    private EstadoPartido mapearEstado(String estado) {
        if (estado == null) return EstadoPartido.PROGRAMADO;
        return switch (estado.toLowerCase()) {
            case "en_vivo"    -> EstadoPartido.EN_VIVO;
            case "finalizado" -> EstadoPartido.FINALIZADO;
            case "cancelado"  -> EstadoPartido.CANCELADO;
            case "aplazado"   -> EstadoPartido.APLAZADO;
            default           -> EstadoPartido.PROGRAMADO;
        };
    }
}

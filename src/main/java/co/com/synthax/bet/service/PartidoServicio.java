package co.com.synthax.bet.service;

import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.EstadoPartido;
import co.com.synthax.bet.proveedor.ProveedorFutbol;
import co.com.synthax.bet.proveedor.modelo.PartidoExterno;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import co.com.synthax.bet.repository.CuotaRepositorio;
import co.com.synthax.bet.repository.PartidoRepositorio;
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
     * Estrategia: UPSERT real (find-by-idPartidoApi + update), NO delete+insert.
     * Esto preserva los IDs de Partido en BD, por lo que los Picks y Cuotas
     * que referencian esos partidos siguen siendo válidos tras la re-sync.
     *
     * Las cuotas y análisis sí se eliminan antes de re-sincronizar porque son
     * datos regenerables — los Picks en cambio son datos del usuario y NUNCA
     * deben borrarse automáticamente.
     *
     * Fix timezone: al hacer upsert, la fechaPartido se actualiza con el valor
     * correcto (timezone=America/Bogota), corrigiendo cualquier registro previo
     * guardado con timezone incorrecto.
     */
    public List<Partido> sincronizarPartidos(LocalDate fecha) {

        LocalDateTime desde = fecha.atTime(0, 0).minusHours(6);   // ayer 18:00
        LocalDateTime hasta = fecha.atTime(23, 59).plusHours(6);   // mañana 06:00

        List<Partido> existentes = partidoRepositorio.findByFechaPartidoBetween(desde, hasta);
        if (!existentes.isEmpty()) {
            List<Long> ids = existentes.stream().map(Partido::getId).toList();

            // Eliminar cuotas y análisis (regenerables). Los Picks NO se tocan
            // porque son datos del usuario — sus referencias a Partido siguen
            // siendo válidas tras el upsert (mismo ID de BD).
            cuotaRepositorio.deleteByPartidoIdIn(ids);
            analisisRepositorio.deleteByPartidoIdIn(ids);

            log.info(">>> Cuotas y análisis de {} partidos borrados antes de re-sincronizar (picks intactos)", existentes.size());
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

        // Persistir goles cuando el partido ya terminó. Esto permite que
        // resolverPendientesAutomatico() resuelva picks desde BD sin consumir
        // requests adicionales a la API.
        if (externo.getGolesLocal() != null)     partido.setGolesLocal(externo.getGolesLocal());
        if (externo.getGolesVisitante() != null) partido.setGolesVisitante(externo.getGolesVisitante());

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

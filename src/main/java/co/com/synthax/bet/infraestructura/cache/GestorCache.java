package co.com.synthax.bet.infraestructura.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestor de caché en memoria.
 * Usa ConcurrentHashMap para almacenar respuestas de la API y evitar
 * re-requests innecesarios dentro del límite diario gratuito (100 req/día).
 *
 * Nota: al reiniciar la aplicación el caché se vacía. Para persistencia
 * entre reinicios se puede incorporar Redis más adelante.
 */
@Slf4j
@Service
public class GestorCache {

    @Value("${statbet.proveedor.cache-segundos:3600}")
    private long cacheTtlSegundos;

    @Value("${statbet.apis.api-football.requests-diarios-max:100}")
    private int requestsMax;

    /** Máximo de requests por minuto permitidos por el plan (ventana deslizante). */
    @Value("${statbet.apis.api-football.requests-por-minuto:25}")
    private int requestsPorMinuto;

    /**
     * Reserva dedicada para cuotas. El análisis (que consume el grueso de
     * requests pidiendo stats por equipo) se autodetiene cuando quedan menos
     * que esta cantidad disponibles. Esto garantiza que la ingesta de cuotas
     * — que es lo que cambia minuto a minuto y de donde sale el edge real —
     * siempre tenga su propio cupo asegurado.
     *
     * Configurable vía property si se cambia el plan de la API.
     */
    @Value("${statbet.apis.api-football.reserva-cuotas:30}")
    private int reservaCuotas;

    /**
     * Margen mínimo de seguridad incluso para operaciones de cuotas.
     * Evita quemar el último request en una llamada que podría fallar y dejar
     * el sistema sin capacidad de retry para nada.
     */
    private static final int MARGEN_MINIMO = 5;

    // Caché de objetos: clave -> valor
    private final ConcurrentHashMap<String, Object> almacen = new ConcurrentHashMap<>();

    // Caché de expiración: clave -> momento en que vence
    private final ConcurrentHashMap<String, LocalDateTime> expiraciones = new ConcurrentHashMap<>();

    // Contador de requests del día
    private final AtomicInteger contadorRequests = new AtomicInteger(0);
    private LocalDate fechaContador = LocalDate.now();

    /**
     * Ventana deslizante de 60 segundos para throttle por minuto.
     * Cada elemento es el Instant en que se ejecutó un request real.
     */
    private final Deque<Instant> ventanaMinuto = new ConcurrentLinkedDeque<>();

    /** Lock para serializar la evaluación del throttle (evita race conditions). */
    private final Object throttleLock = new Object();

    // -------------------------------------------------------
    // Guardar y recuperar objetos
    // -------------------------------------------------------

    public void guardar(String clave, Object valor) {
        guardar(clave, valor, cacheTtlSegundos);
    }

    public void guardar(String clave, Object valor, long ttlSegundos) {
        almacen.put(clave, valor);
        expiraciones.put(clave, LocalDateTime.now().plusSeconds(ttlSegundos));
        log.debug(">>> Cache GUARDADO: {} (TTL: {}s)", clave, ttlSegundos);
    }

    @SuppressWarnings("unchecked")
    public <T> T obtener(String clave, Class<T> tipo) {
        LocalDateTime vencimiento = expiraciones.get(clave);

        if (vencimiento == null || LocalDateTime.now().isAfter(vencimiento)) {
            almacen.remove(clave);
            expiraciones.remove(clave);
            log.debug(">>> Cache MISS (vencido o inexistente): {}", clave);
            return null;
        }

        Object valor = almacen.get(clave);
        if (valor == null) return null;

        log.debug(">>> Cache HIT: {}", clave);
        return (T) valor;
    }

    public void eliminar(String clave) {
        almacen.remove(clave);
        expiraciones.remove(clave);
    }

    public boolean existe(String clave) {
        LocalDateTime vencimiento = expiraciones.get(clave);
        return vencimiento != null && LocalDateTime.now().isBefore(vencimiento);
    }

    // -------------------------------------------------------
    // Control de requests diarios
    // -------------------------------------------------------

    /**
     * Registra un request consumido hoy.
     * Si cambia el día, reinicia el contador automáticamente.
     */
    public void registrarRequest() {
        reiniciarContadorSiEsNuevoDia();
        int total = contadorRequests.incrementAndGet();
        log.info(">>> Requests API hoy: {}/{}", total, requestsMax);
    }

    /**
     * Devuelve el total de requests usados hoy.
     */
    public int contarRequestsHoy() {
        reiniciarContadorSiEsNuevoDia();
        return contadorRequests.get();
    }

    /**
     * Verifica si todavía hay requests disponibles para operaciones de uso
     * general (sincronización de partidos, stats de equipos, árbitros, etc.).
     *
     * Reserva `reservaCuotas` requests intocables para que la ingesta de cuotas
     * siempre tenga cupo, sin importar cuánto haya quemado el análisis. Antes
     * la reserva era solo de 5 y el análisis consumía todo el presupuesto,
     * dejando la ingesta de cuotas sin posibilidad de ejecutarse.
     */
    public boolean puedeHacerRequest() {
        reiniciarContadorSiEsNuevoDia();
        int disponibles = requestsMax - contadorRequests.get();
        boolean puede   = disponibles > reservaCuotas;
        if (!puede) {
            log.warn(">>> Límite alcanzado para uso general: {}/{} (reserva {} para cuotas)",
                    contadorRequests.get(), requestsMax, reservaCuotas);
        }
        return puede;
    }

    /**
     * Verifica si hay requests disponibles para la ingesta de cuotas.
     *
     * Solo respeta el margen mínimo de seguridad (5), no la reserva grande.
     * Esto permite que cuotas use TODO el budget reservado para ella sin
     * tropezar con su propio límite.
     */
    public boolean puedeHacerRequestParaCuotas() {
        reiniciarContadorSiEsNuevoDia();
        int disponibles = requestsMax - contadorRequests.get();
        boolean puede   = disponibles > MARGEN_MINIMO;
        if (!puede) {
            log.warn(">>> Límite alcanzado incluso para cuotas: {}/{}",
                    contadorRequests.get(), requestsMax);
        }
        return puede;
    }

    /**
     * Verifica si hay requests disponibles para resolver picks pendientes.
     *
     * Usa el mismo umbral mínimo que las cuotas (MARGEN_MINIMO) para que la
     * resolución funcione aunque el budget de análisis general esté agotado.
     * La resolución es crítica — si no se ejecuta, los picks quedan PENDIENTE
     * indefinidamente aunque los partidos ya hayan terminado.
     */
    public boolean puedeHacerRequestParaResolucion() {
        reiniciarContadorSiEsNuevoDia();
        int disponibles = requestsMax - contadorRequests.get();
        boolean puede   = disponibles > MARGEN_MINIMO;
        if (!puede) {
            log.warn(">>> Sin cupo para resolver picks: {}/{} requests usados — picks quedarán PENDIENTE",
                    contadorRequests.get(), requestsMax);
        }
        return puede;
    }

    /**
     * Devuelve el total de requests disponibles para uso general (descontando
     * la reserva de cuotas). Útil para mostrar en UI cuántos quedan para
     * análisis sin afectar la ingesta de cuotas posterior.
     */
    public int requestsDisponiblesUsoGeneral() {
        reiniciarContadorSiEsNuevoDia();
        return Math.max(0, requestsMax - contadorRequests.get() - reservaCuotas);
    }

    /**
     * Devuelve los requests realmente disponibles (incluida la reserva de
     * cuotas). Esto es lo que la ingesta de cuotas puede consumir.
     */
    public int requestsDisponiblesParaCuotas() {
        reiniciarContadorSiEsNuevoDia();
        return Math.max(0, requestsMax - contadorRequests.get() - MARGEN_MINIMO);
    }

    /**
     * Cuántos requests están reservados para cuotas (información para la UI).
     */
    public int reservaCuotas() {
        return reservaCuotas;
    }

    /**
     * Tope total diario de la API (para UI/diagnóstico).
     */
    public int requestsMax() {
        return requestsMax;
    }

    // -------------------------------------------------------
    // Throttle por minuto (ventana deslizante)
    // -------------------------------------------------------

    /**
     * Bloquea el hilo hasta que sea seguro hacer otro request sin violar el
     * límite por minuto del plan activo.
     *
     * Algoritmo: ventana deslizante de 60 segundos.
     *  1. Elimina del deque todos los timestamps con más de 60 segundos de antigüedad.
     *  2. Si el deque tiene ≥ requestsPorMinuto entradas, calcula cuánto tiempo hay
     *     que esperar hasta que el más antiguo "salga" de la ventana y duerme ese tiempo.
     *  3. Añade el timestamp actual y retorna — el llamador puede hacer su request.
     *
     * Sincronizado para que análisis concurrentes no se pisoteen.
     */
    public void esperarSiNecesario() {
        synchronized (throttleLock) {
            Instant ahora = Instant.now();
            Instant ventanaInicio = ahora.minusSeconds(60);

            // Limpiar entradas fuera de la ventana de 1 minuto
            ventanaMinuto.removeIf(t -> t.isBefore(ventanaInicio));

            if (ventanaMinuto.size() >= requestsPorMinuto) {
                // El más antiguo dentro de la ventana: cuándo podremos descartarlo
                Instant masAntiguo = ventanaMinuto.peekFirst();
                if (masAntiguo != null) {
                    // Tiempo hasta que ese request "caiga" fuera de la ventana
                    long msEspera = masAntiguo.plusSeconds(60).toEpochMilli()
                            - Instant.now().toEpochMilli() + 200L; // +200ms de margen
                    if (msEspera > 0) {
                        log.info(">>> [THROTTLE] {}/{} req/min alcanzados — esperando {}ms",
                                requestsPorMinuto, requestsPorMinuto, msEspera);
                        try {
                            Thread.sleep(msEspera);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                // Re-limpiar tras la espera
                Instant nuevo = Instant.now();
                ventanaMinuto.removeIf(t -> t.isBefore(nuevo.minusSeconds(60)));
            }

            ventanaMinuto.addLast(Instant.now());
        }
    }

    // -------------------------------------------------------
    // Helper privado
    // -------------------------------------------------------

    private void reiniciarContadorSiEsNuevoDia() {
        LocalDate hoy = LocalDate.now();
        if (!hoy.equals(fechaContador)) {
            contadorRequests.set(0);
            fechaContador = hoy;
            log.info(">>> Contador de requests reiniciado para {}", hoy);
        }
    }
}

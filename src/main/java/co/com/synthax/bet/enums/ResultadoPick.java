package co.com.synthax.bet.enums;

public enum ResultadoPick {
    PENDIENTE,  // partido aún no jugado
    GANADO,     // pick correcto - WIN
    PERDIDO,    // pick incorrecto - LOSS
    NULO        // pick anulado por suspensión u otro motivo - VOID
}

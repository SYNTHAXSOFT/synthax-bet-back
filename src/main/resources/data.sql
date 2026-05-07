INSERT IGNORE INTO usuarios (nombre, email, password, rol, activo, fecha_creacion)
VALUES ('Admin', 'admin@synthax.com', '123456', 'ADMINISTRADOR', true, NOW());

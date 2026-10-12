# TCL

Flyway ejecuta cada migración PostgreSQL en una transacción. No hay operaciones
TCL adicionales aprobadas. Customers API debe escribir cliente y clave de
idempotencia dentro de su propia transacción; el runner no reemplaza esa lógica.

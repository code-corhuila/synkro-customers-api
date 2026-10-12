# Prepared correction — synkro-docs#247 (not submitted)

Source: explicit user approval in this task on 2026-10-12. Replace the truncated
CLI-API-16 and add CLI-API-17 with the following complete approved text. This file
does not update the GitHub issue, close the HU, or change another repository.

## CLI-API-16 — Integración

El servicio real de Customers sustituye al participante controlado en la validación
coordinada de Workflow. Se verifica registro con cliente activo y rechazo de clientes
inactivos o inexistentes, utilizando los servicios reales de Customers, Products y
Sales. El rechazo del cliente no debe producir reservas ni ventas. Registra los SHA
ejecutados y distingue esta evidencia de las pruebas controladas. Si falta una
dependencia, marca este criterio BLOCKED; no lo sustituyas por mocks.

## CLI-API-17 — Entrega

CI, cobertura, arquitectura hexagonal, imagen y configuración de despliegue interno
aprueban sobre el SHA revisado. El servicio utiliza customers_app y no genera ni
modifica el esquema. CODEOWNERS y migraciones de repositorios ajenos permanecen
intactos.

## Related documentary corrections

- Use HU-VEN-12 / synkro-docs#246 for Sales Portal references currently labelled
  HU-SALES-12.
- Use the public contract field registrationDate; correct the DB handoff example
  labelled registeredAt without changing the database column registration_date.
- Link the acceptance matrix and exact-SHA evidence; coordinated WKF-16 publication
  remains with Workflow's owner. Do not mark the complete platform Done.
